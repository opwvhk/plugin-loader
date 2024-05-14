package opwvhk.plugin;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestTag;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.logging.Logger;

import static java.util.Collections.emptyEnumeration;
import static java.util.Collections.enumeration;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilteringClassLoaderTest {
	@Test
	void pocResourceLocations() throws IOException, URISyntaxException {
		assertThat(classpathEntry(String.class)).isNull();
		assertThat(resourceLocation(String.class)).hasProtocol("jrt");

		URL location = resourceLocation(TestTag.class);
		assertThat(location).hasProtocol("jar");
		URLConnection connection = location.openConnection();
		assertThat(connection).isInstanceOf(JarURLConnection.class);
		URL jarLocation = ((JarURLConnection) connection).getJarFileURL();
		assertThat(jarLocation).isEqualTo(classpathEntry(TestTag.class));

		URL resourcedLocation = resourceLocation(FilteringClassLoader.class);
		URL classpathLocation = classpathEntry(FilteringClassLoader.class);
		URI relativeLocation = requireNonNull(classpathLocation).toURI().relativize(resourcedLocation.toURI());
		String internalName = FilteringClassLoader.class.getName().replace('.', '/') + ".class";
		assertThat(relativeLocation.getPath()).isEqualTo(internalName);

		// checkCodeLocation(String.class, "jrt:");
	}

	private static URL classpathEntry(Class<?> clazz) {
		CodeSource codeSource = clazz.getProtectionDomain().getCodeSource();
		return codeSource == null ? null : codeSource.getLocation();
	}

	private URL resourceLocation(Class<?> clazz) {
		String resource = getResourceName(clazz);
		return getClass().getResource(resource);
	}

	@NotNull
	private static String getResourceName(Class<?> clazz) {
		return "/" + clazz.getName().replace(".", "/") + ".class";
	}

	@Test
	void checkClassLoadingIsStable() throws ClassNotFoundException {
		FilteringClassLoader loader = FilteringClassLoader
				.using(ClassLoader.getSystemClassLoader())
				.withName("stability")
				.withCodeSource(FilteringClassLoader.class.getProtectionDomain().getCodeSource().getLocation())
				.build();

		assertThat(loader.getName()).isEqualTo("stability");

		Class<?> class1 = loader.loadClass("opwvhk.plugin.FilteringClassLoader", false);
		Class<?> class2 = loader.loadClass("opwvhk.plugin.FilteringClassLoader", true);
		assertThat(class1).isSameAs(class2);
	}

	@Test
	void checkClassFiltering() throws ClassNotFoundException {
		ClassLoader parent = ClassLoader.getSystemClassLoader();
		FilteringClassLoader loader = FilteringClassLoader.using(parent).withCodeSourcesOf(String.class) // A functional no-op
				.withCodeSourcesOf(FilteringClassLoader.class).build();

		// Verify that the parent can load anything
		assertThat(parent.loadClass("java.lang.String")).isSameAs(String.class);
		assertThat(parent.loadClass("org.junit.platform.engine.TestTag")).isSameAs(TestTag.class);
		assertThat(parent.loadClass("opwvhk.plugin.FilteringClassLoader")).isSameAs(FilteringClassLoader.class);

		// Verify that the filtering classloader cannot.
		assertThat(loader.loadClass("java.lang.String")).isSameAs(String.class);
		assertThatThrownBy(() -> loader.loadClass("org.junit.platform.engine.TestTag")).isInstanceOf(ClassNotFoundException.class);
		assertThat(loader.loadClass("opwvhk.plugin.FilteringClassLoader")).isSameAs(FilteringClassLoader.class);
	}

	@Test
	void checkResourceFiltering() throws IOException {
		Class<?> allowedClass = Logger.class;
		String allowedResource = getResourceName(allowedClass);
		URL allowedLocation = requireNonNull(resourceLocation(allowedClass));
		Class<?> otherClass = (TestTag.class);
		String otherResource = getResourceName(otherClass);
		URL otherLocation = requireNonNull(resourceLocation(otherClass));
		String missingResource = "package.of.MissingClass";
		String brokenResource = "package.of.BrokenClass";

		ClassLoader mockLoader = new ClassLoader() {
			@Override
			public Enumeration<URL> getResources(String name) throws IOException {
				if (name.equals(allowedResource)) {
					return enumeration(List.of(allowedLocation));
				} else if (name.equals(otherResource)) {
					return enumeration(List.of(otherLocation));
				} else if (name.equals(missingResource)) {
					return emptyEnumeration();
				} else {
					throw new IOException("Oops");
				}
			}
		};
		FilteringClassLoader loader = FilteringClassLoader.using(mockLoader)
				.withCodeSourcesOf(allowedClass).build();

		assertThat(loader.getResource(allowedResource)).isEqualTo(allowedLocation);
		assertThat(loader.getResource(otherResource)).isNull();
		assertThat(loader.getResource(missingResource)).isNull();
		assertThat(loader.getResource(brokenResource)).isNull();

		assertThat(Collections.list(loader.getResources(allowedResource))).containsExactly(allowedLocation);
		assertThat(Collections.list(loader.getResources(otherResource))).isEmpty();
		assertThatThrownBy(() -> loader.getResources(brokenResource)).isInstanceOf(IOException.class);
	}

	@Test
	void validateBuilderForCodeSources() {
		FilteringClassLoader.Builder builder1 = FilteringClassLoader.using(ClassLoader.getSystemClassLoader())
				.withCodeSourcesOf(String.class, TestTag.class, FilteringClassLoader.class);
		assertThat(builder1.isAllowedClass(String.class)).isTrue();
		assertThat(builder1.isAllowedClass(TestTag.class)).isTrue();
		assertThat(builder1.isAllowedClass(FilteringClassLoader.class)).isTrue();

		assertThat(builder1.isAllowedResource(requireNonNull(getClass().getResource(getResourceName(String.class))))).isTrue();
		assertThat(builder1.isAllowedResource(requireNonNull(getClass().getResource(getResourceName(TestTag.class))))).isTrue();
		assertThat(builder1.isAllowedResource(requireNonNull(getClass().getResource(getResourceName(FilteringClassLoader.class))))).isTrue();

		FilteringClassLoader.Builder builder2 = FilteringClassLoader.using(ClassLoader.getSystemClassLoader());
		assertThat(builder2.isAllowedClass(String.class)).isTrue();
		assertThat(builder2.isAllowedClass(TestTag.class)).isFalse();
		assertThat(builder2.isAllowedClass(FilteringClassLoader.class)).isFalse();

		assertThat(builder2.isAllowedResource(requireNonNull(getClass().getResource(getResourceName(String.class))))).isTrue();
		assertThat(builder2.isAllowedResource(requireNonNull(getClass().getResource(getResourceName(TestTag.class))))).isFalse();
		assertThat(builder2.isAllowedResource(requireNonNull(getClass().getResource(getResourceName(FilteringClassLoader.class))))).isFalse();
	}

	@Test
	void checkFilteredEnumeration() {
		Enumeration<Integer> numbers = enumeration(List.of(1, 2, 3, 4));
		Enumeration<Integer> even = FilteringClassLoader.filter(numbers, i -> i % 2 == 0);
		ArrayList<Integer> list = Collections.list(even);
		assertThat(list).containsExactly(2, 4);
	}

	@Test
	@SuppressWarnings("ConstantValue")
	void checkFilteredEnumerationEdgeCases() {
		Enumeration<String> e = FilteringClassLoader.filter(enumeration(List.of("a", "b", "c")), "b"::equals);
		assertThat(e.hasMoreElements()).isTrue();
		assertThat(e.hasMoreElements()).isTrue();
		assertThat(e.nextElement()).isEqualTo("b");
		assertThatThrownBy(e::nextElement).isInstanceOf(NoSuchElementException.class);
	}
}
