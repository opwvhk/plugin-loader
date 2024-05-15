package opwvhk.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import static java.util.Collections.singleton;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginsTest {
	private static final String METADATA_NAME = "plugin.properties";
	private static final String METADATA_CONTENT = "name=My First Plugin\n";
	private static final String NOT_A_FILE = "not_a_file";
	private ClassLoader parentClassLoader;
	@TempDir
	Path pluginFolder;
	Path plugin1Path;
	Path plugin2Path;
	private Plugin plugin1;
	private Plugin plugin2;

	@BeforeEach
	void setupPlugins() throws IOException {
		String fooImplEntry = FooImpl.class.getName().replace('.', '/') + ".class";
		byte[] fooBytes = readResource("FooImpl.class");
		String serviceEntry = "META-INF/services/" + FooService.class.getName();
		byte[] serviceBytes = (FooImpl.class.getName() + "\n").getBytes(StandardCharsets.UTF_8);
		byte[] fooZip = createJar(Map.of(fooImplEntry, fooBytes, serviceEntry, serviceBytes));

		plugin1Path = Files.createDirectories(pluginFolder.resolve("plugin1"));
		Files.createDirectory(plugin1Path.resolve("emptyClassPathEntry"));
		Files.write(plugin1Path.resolve("foo.jar"), fooZip);
		Files.writeString(plugin1Path.resolve(METADATA_NAME), METADATA_CONTENT);
		Files.createSymbolicLink(plugin1Path.resolve(NOT_A_FILE), plugin1Path.resolve("missing"));

		plugin2Path = Files.write(pluginFolder.resolve("plugin2.jar"), fooZip);

		Files.write(pluginFolder.resolve("random_file.txt"), List.of("I should be ignored"));

		parentClassLoader = new FilteringClassLoader("test", ClassLoader.getSystemClassLoader(), c -> !c.equals(FooImpl.class), r -> false);
		Plugins plugins = new Plugins(singleton(pluginFolder), parentClassLoader);
		Map<String, Plugin> pluginsByName = plugins.getPlugins().stream().collect(Collectors.toMap(Plugin::getName, Function.identity()));
		plugin1 = pluginsByName.get("plugin1");
		plugin2 = pluginsByName.get("plugin2");
	}

	private byte[] readResource(@SuppressWarnings("SameParameterValue") String name) throws IOException {
		try (InputStream fooStream = requireNonNull(getClass().getResourceAsStream(name))) {
			return fooStream.readAllBytes();
		}
	}

	byte[] createJar(Map<String, byte[]> fileContentsByName) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		try (JarOutputStream stream = new JarOutputStream(buffer, manifest)) {
			for (Map.Entry<String, byte[]> entry : fileContentsByName.entrySet()) {
				byte[] bytes = entry.getValue();
				ZipEntry zipEntry = new ZipEntry(entry.getKey());
				zipEntry.setSize(bytes.length);
				stream.putNextEntry(zipEntry);
				stream.write(bytes);
				stream.closeEntry();
			}
		}
		return buffer.toByteArray();
	}

	@Test
	void validatePlugins() throws IOException {
		//@start region="quickstart"
		Plugins plugins = new Plugins(pluginFolder, FooService.class);
		//@end region="quickstart"
		Map<String, Plugin> pluginsByName = plugins.getPlugins().stream().collect(Collectors.toMap(Plugin::getName, Function.identity()));
		assertThat(pluginsByName).hasSize(2).containsKeys("plugin1", "plugin2");
		assertThat(pluginsByName.get("plugin1").getName()).isEqualTo("plugin1");
		assertThat(pluginsByName.get("plugin2").getName()).isEqualTo("plugin2");

		// Not a service class
		assertThat(plugins.getServices(Plugins.class)).isEmpty();

		Iterable<FooService> fooServices = plugins.getServices(FooService.class);
		assertThat(fooServices).hasSize(2);
		assertThat(fooServices).map(FooService::foo).containsExactly("bar", "bar");
	}

	@Test
	void verifyClassIsolation() {
		FooService fooService0 = new FooImpl();
		String fooServiceClassName = fooService0.getClass().getName();

		assertThat(fooService0.foo()).isEqualTo("bar");

		FooService fooService1 = plugin1.getServiceLoader(FooService.class).findFirst().orElse(null);
		assertThat(fooService1).isNotNull().isInstanceOf(FooService.class);
		assertThat(fooService1.getClass().getName()).isEqualTo(fooServiceClassName);
		assertThat(fooService1.getClass()).isNotEqualTo(fooService0.getClass());
		assertThat(fooService1.foo()).isEqualTo("bar");

		FooService fooService2 = plugin2.getServiceLoader(FooService.class).findFirst().orElse(null);
		assertThat(fooService2).isNotNull().isInstanceOf(FooService.class);
		assertThat(fooService2.getClass().getName()).isEqualTo(fooServiceClassName);
		assertThat(fooService2.getClass()).isNotEqualTo(fooService0.getClass());
		assertThat(fooService2.foo()).isEqualTo("bar");
	}

	@Test
	void verifyPluginMetadata() {
		assertThat(plugin1.loadTextMetadata(METADATA_NAME)).isEqualTo(METADATA_CONTENT);

		byte[] binaryMetadata = plugin1.loadBinaryMetadata(METADATA_NAME);
		assertThat(binaryMetadata).containsExactly(METADATA_CONTENT.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void verifyPluginLoadingFailure() {
		Set<Path> pluginPath = singleton(plugin1Path.resolve(METADATA_NAME));
		assertThatThrownBy(() -> new Plugins(pluginPath, parentClassLoader)).isInstanceOf(IOException.class);
	}

	@Test
	void verifyPluginMetadataFailures() {
		assertThat(plugin2.loadBinaryMetadata(null)).isNull();
		assertThat(plugin1.loadBinaryMetadata(NOT_A_FILE)).isNull();
	}
}
