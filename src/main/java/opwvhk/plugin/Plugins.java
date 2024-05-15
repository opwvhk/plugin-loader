package opwvhk.plugin;

import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;

/**
 * <p>Entry point to use plugins.</p>
 *
 * <p>Loads plugins for the specified service classes (and only those service classes) using the context classloader from the specified plugin path.</p>
 *
 * <p>Uses a {@link FilteringClassLoader} on the context class loader to allow the classpath entries for the specified service classes (this acts like a
 * firewall, keeping your application code off limits), and allows only those service classes to be loaded (to guarantee they are available).</p>
 *
 * @see Plugin
 * @see FilteringClassLoader
 */
public class Plugins {
	private static final System.Logger LOG = System.getLogger(Plugins.class.getCanonicalName());
	private final Collection<Plugin> plugins;

	/**
	 * Load plugins for the specified service classes (and only those service classes) using the context classloader from the specified plugin path.
	 *
	 * @param pluginPath     the plugin path
	 * @param serviceClasses the service classes to expose
	 * @throws IOException when the plugins could not be loaded
	 */
	public Plugins(Path pluginPath, Class<?>... serviceClasses) throws IOException {
		this(Collections.singleton(pluginPath), serviceClasses);
	}

	/**
	 * Load plugins for the specified service classes (and only those service classes) using the context classloader from the specified plugin path.
	 *
	 * @param pluginPath     the plugin path
	 * @param serviceClasses the service classes to expose
	 * @throws IOException when the plugins could not be loaded
	 */
	public Plugins(Set<Path> pluginPath, Class<?>... serviceClasses) throws IOException {
		this(pluginPath, createPluginParentClassLoader(serviceClasses));
	}

	private static ClassLoader createPluginParentClassLoader(Class<?>[] serviceClasses) {
		ClassLoader classLoader = Optional.ofNullable(Thread.currentThread().getContextClassLoader()).orElseGet(ClassLoader::getSystemClassLoader);
		return FilteringClassLoader.using(classLoader).withCodeSourcesOf(serviceClasses).build();
	}

	@VisibleForTesting
	Plugins(Set<Path> pluginPath, ClassLoader pluginParentClassLoader) throws IOException {
		LOG.log(INFO, "Loading plugins from {0}", pluginPath);
		plugins = new ArrayList<>();
		for (Path pluginPathElement : pluginPath) {
			Files.walkFileTree(pluginPathElement, EnumSet.noneOf(FileVisitOption.class), 2, new SimpleFileVisitor<>() {
				private boolean startedInDirectory = false;
				private Path pluginBasePath;
				private List<Path> classpath;

				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					if (!startedInDirectory) {
						startedInDirectory = true;
					} else {
						LOG.log(DEBUG, "Found directory plugin at {0}", dir);
						pluginBasePath = dir;
						classpath = new ArrayList<>();
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
					if (!startedInDirectory) {
						throw new IOException("A plugin directory must be a directory");
					} else if (isFileOrDirectory(attrs)) {
						if (classpath != null) {
							LOG.log(DEBUG, "Found classpath entry: {0}", path);
							if (attrs.isDirectory() || path.getFileName().toString().endsWith(".jar")) {
								classpath.add(path);
							}
						} else if (path.getFileName().toString().endsWith(".jar")){
							LOG.log(DEBUG, "Found .jar-only plugin at {0}", path);
							Plugin plugin = new Plugin(pluginParentClassLoader, path);
							LOG.log(INFO, "Created plugin {0}", plugin.getName());
							plugins.add(plugin);
						}
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					// classpath is null when terminating at the pluginPathElement
					if (classpath != null) {
						Plugin plugin = new Plugin(pluginParentClassLoader, pluginBasePath, classpath);
						LOG.log(INFO, "Created plugin {0}", plugin.getName());
						plugins.add(plugin);

						pluginBasePath = null;
						classpath = null;
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}

	}

	@VisibleForTesting
	boolean isFileOrDirectory(BasicFileAttributes attrs) {
		return attrs.isRegularFile() || attrs.isDirectory();
	}

	/**
	 * Get all implementations of the service class across all plugins.
	 *
	 * @param serviceClass a service class
	 * @return all services provided by the plugins
	 */
	public <T> Iterable<T> getServices(Class<T> serviceClass) {
		return plugins.stream()
				.flatMap(p -> p.getServiceLoader(serviceClass).stream())
				.map(ServiceLoader.Provider::get)
				.toList();
	}

	/**
	 * Return all loaded plugins.
	 *
	 * @return all loaded plugins
	 */
	public Collection<Plugin> getPlugins() {
		return Collections.unmodifiableCollection(plugins);
	}
}
