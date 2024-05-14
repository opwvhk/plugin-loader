package opwvhk.plugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * A simple plugin: wraps a classpath and optional metadata directory, and provides access to its services and metadata.
 *
 * <p>A plugin consists of a single far jar, or a directory with all classpath entries (package directories and/or jar files) and metadata (regular files).
 * Anything other than files and directories is ignored.</p>
 *
 * <p>Plugins can be loaded from a specific path, or a directory containing any number of plugins.</p>
 *
 * <p>Note to users: use a {@link FilteringClassLoader} or similar to avoid exposing your non-API classes.</p>
 */
public class Plugin {
	private final String name;
	private final Path metadataPath;
	private final NavigableSet<Path> classpathEntries;
	private final ClassLoader classLoader;
	private final WeakHashMap<Class<?>, ServiceLoader<?>> serviceLoaders;

	Plugin(ClassLoader parentClassLoader, Path jarPath) throws IOException {
		this(jarPath.getFileName().toString().substring(0, jarPath.getFileName().toString().length() - 4), null, parentClassLoader, List.of(jarPath));
	}

	Plugin(ClassLoader parentClassLoader, Path metadataPath, List<Path> additionalClasspath) throws IOException {
		this(metadataPath.getFileName().toString(), metadataPath, parentClassLoader, additionalClasspath);
	}

	/**
	 * Create a plugin. All paths in {@code additionalClasspath} are assumed to be in the {@code metadataPath}, unless {@code metadataPath} is {@code null} and
	 * then {@code additionalClasspath} should contain exactly one entry.
	 *
	 * @param name                the name of the plugin
	 * @param metadataPath        the metadata path, if any
	 * @param parentClassLoader   the parent classloader
	 * @param additionalClasspath the plugin classpath in addition to the parent classpath
	 */
	private Plugin(@NotNull String name, @Nullable Path metadataPath, @NotNull ClassLoader parentClassLoader, @NotNull List<Path> additionalClasspath)
			throws IOException {
		this.name = name;
		this.metadataPath = metadataPath == null ? null : metadataPath.normalize().toAbsolutePath();

		classpathEntries = new TreeSet<>();
		URL[] classpath = new URL[additionalClasspath.size()];
		int i = 0;
		for (Path path : additionalClasspath) {
			Path absolutePath = path.normalize().toAbsolutePath();
			// The load method ensures the additional classpath is a single entry, or multiple entries within a plugin directory.
			classpathEntries.add(absolutePath);
			classpath[i++] = absolutePath.toUri().toURL();
		}
		classLoader = new URLClassLoader(classpath, parentClassLoader);
		serviceLoaders = new WeakHashMap<>();
	}

	public String getName() {
		return name;
	}

	/**
	 * Get the {@code ServiceLoader} for a given plugin service. Uses the (isolated) plugin classloader to load classes.
	 *
	 * @param service the plugin service
	 * @return the service loader
	 */
	public <T> ServiceLoader<T> getServiceLoader(Class<T> service) {
		// noinspection unchecked
		return (ServiceLoader<T>) serviceLoaders.computeIfAbsent(service, s -> ServiceLoader.load(service, classLoader));
	}

	/**
	 * <p>Load the named metadata for the plugin.</p>
	 *
	 * <p>Equivalent to {@code loadMetadata(metadataName, Function.identity())}</p>
	 *
	 * @param metadataName the name of the metadata file
	 * @return the metadata if it can be loaded, {@code null} otherwise
	 */
	public byte[] loadBinaryMetadata(String metadataName) {
		return loadMetadata(metadataName, Function.identity());
	}

	/**
	 * <p>Load UTF-8 textual metadata for the plugin.</p>
	 *
	 * <p>Equivalent to {@code loadTextMetadata(metadataName, StandardCharsets.UTF_8)}</p>
	 *
	 * @param metadataName the name of the metadata
	 * @return the metadata if it can be loaded, {@code null} otherwise
	 * @see #loadTextMetadata(String, Charset)
	 */
	public String loadTextMetadata(String metadataName) {
		return loadTextMetadata(metadataName, StandardCharsets.UTF_8);
	}

	/**
	 * <p>Load textual metadata for the plugin.</p>
	 *
	 * <p>Equivalent to {@code loadMetadata(metadataName, bytes -> new String(bytes, charset))}</p>
	 *
	 * @param metadataName the name of the metadata
	 * @param charset      the character set to use to load the metadata
	 * @return the metadata if it can be loaded, {@code null} otherwise
	 */
	public String loadTextMetadata(String metadataName, Charset charset) {
		return loadMetadata(metadataName, bytes -> new String(bytes, charset));
	}

	/**
	 * <p>Load arbitrary metadata for all plugins.</p>
	 *
	 * <p>Any exception thrown by the mapping function is propagated.</p>
	 *
	 * @param metadataName the name of the metadata
	 * @param mapper       a function mapping the bytes read to the metadata
	 * @return the metadata if it can be loaded, {@code null} otherwise
	 */
	public <T> T loadMetadata(String metadataName, Function<byte[], T> mapper) {
		return Optional.ofNullable(metadataPath)
				.map(mdp -> mdp.resolve(metadataName))
				.map(Path::normalize)
				.map(Path::toAbsolutePath)
				.filter(mdp -> Optional.ofNullable(classpathEntries.floor(mdp))
						.filter(mdp::startsWith)
						// If not empty, we're accessing the plugin classpath, which is not metadata
						.isEmpty())
				.map(metadataFilePath -> {
					try {
						return Files.readAllBytes(metadataFilePath);
					} catch (IOException ignored) {
						return null;
					}
				})
				.map(mapper)
				.orElse(null);
	}
}
