package opwvhk.plugin;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * <p>Filtering classloader: acts as a firewall between your application code and any custom classloaders you may want to build.</p>
 *
 * <p>This classloader only allows classes and resources from the parent classloader if they're either system classes/resources or belong
 * to the same code sources (jars/directories) as the classes specified at startup. It does NOT discover dependencies: you'll have to do
 * that yourself.</p>
 */
public class FilteringClassLoader extends ClassLoader {
	private static final AtomicInteger counter = new AtomicInteger(1);
	private final Predicate<Class<?>> classFilter;
	private final Predicate<URL> resourceFilter;
	private final Map<String, Class<?>> returnedClasses;

	static {
		registerAsParallelCapable();
	}

	/**
	 * Create a builder to create a {@code FilteringClassLoader} filtering a given parent classloader.
	 *
	 * @param parent the parent classloader, whose classes to filter
	 * @return a builder
	 */
	public static FilteringClassLoader.Builder using(ClassLoader parent) {
		return new Builder(parent);
	}


	@VisibleForTesting
	FilteringClassLoader(String name, ClassLoader parent, Predicate<Class<?>> classFilter, Predicate<URL> resourceFilter) {
		super(name, parent);
		this.classFilter = classFilter;
		this.resourceFilter = resourceFilter;
		returnedClasses = new HashMap<>();
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		synchronized (getClassLoadingLock(name)) {
			// First, check if the class has already been loaded
			// Note 1: findLoadedClass(String) doesn't work here, as this classloader doesn't load classes itself.
			// Note 2: Map.computeIfAbsent does not work here, as we need to throw a ClassNotFoundException.
			Class<?> c = returnedClasses.get(name);
			if (c == null) {
				try {
					c = ClassLoader.getPlatformClassLoader().loadClass(name);
				} catch (ClassNotFoundException e) {
					// Ignore: we'll try another way
				}
			}
			if (c == null) {
				c = getParent().loadClass(name);
				if (!classFilter.test(c)) {
					throw new ClassNotFoundException(name);
				}
			}
			returnedClasses.put(name, c);
			if (resolve) {
				resolveClass(c);
			}
			return c;
		}
	}

	@Nullable
	@Override
	public URL getResource(String name) {
		try {
			Enumeration<URL> resources = getResources(name);
			if (resources.hasMoreElements()) {
				return resources.nextElement();
			}
		} catch (IOException e) {
			// Ignore: we'll return null instead.
		}
		return null;
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		final Enumeration<URL> resources = getParent().getResources(name);
		return filter(resources, resourceFilter);
	}

	/**
	 * A builder to create a {@link FilteringClassLoader}.
	 */
	public static class Builder {
		private final ClassLoader parent;
		private final Set<String> allowedCodeSourcePaths;
		private final NavigableSet<String> allowedResourcePrefixes;
		private String name;

		private Builder(ClassLoader parent) {
			this.parent = parent;
			allowedCodeSourcePaths = new HashSet<>();
			allowedResourcePrefixes = new TreeSet<>();
			name = null;
		}

		/**
		 * When creating the classloader, use this name. If not used, the classloader will get a name derived from the parent classloader.
		 *
		 * @param name the name for the classloader
		 * @return this builder
		 */
		public Builder withName(String name) {
			this.name = name;
			return this;
		}

		/**
		 * Allow the classloader to load classes and resources from the code sources (classpath entries) of all provided classes.
		 *
		 * @param sampleClasses the classes whose code sources to allow
		 * @return this builder
		 */
		public Builder withCodeSourcesOf(Class<?>... sampleClasses) {
			Stream.of(sampleClasses).map(Class::getProtectionDomain).map(ProtectionDomain::getCodeSource)
					// JRE classes yield null values, but they are always allowed anyway.
					.filter(Objects::nonNull).map(CodeSource::getLocation).forEach(this::withCodeSource);
			return this;
		}

		/**
		 * Allow the classloader to load classes and resources from the specified code source. If the URL does not belong to the classpath, the behaviour is
		 * undefined.
		 *
		 * @param classpathEntry the classpath entry to allow
		 * @return this builder
		 */
		public Builder withCodeSource(URL classpathEntry) {
			String classpathEntryPath = classpathEntry.getPath();
			allowedCodeSourcePaths.add(classpathEntryPath);
			if (classpathEntryPath.endsWith(".jar")) {
				allowedResourcePrefixes.add(classpathEntry.toExternalForm());
			} else {
				allowedResourcePrefixes.add(classpathEntryPath);
			}
			return this;
		}

		/**
		 * Create a {@link FilteringClassLoader}.
		 *
		 * @return a {@code FilteringClassLoader}
		 */
		public FilteringClassLoader build() {
			String clNMame = name != null ? name : parent.getName() + "-filtered-" + counter.getAndIncrement();
			return new FilteringClassLoader(clNMame, parent, this::isAllowedClass, this::isAllowedResource);
		}

		@VisibleForTesting
		boolean isAllowedClass(Class<?> clazz) {
			CodeSource codeSource = clazz.getProtectionDomain().getCodeSource();
			// Java runtime classes have codeSource==null
			return codeSource == null || allowedCodeSourcePaths.contains(codeSource.getLocation().getPath());
		}

		@VisibleForTesting
		boolean isAllowedResource(URL element) {
			if ("jrt".equals(element.getProtocol())) {
				return true;
			} else {
				String elementPath = element.getPath();
				// The stored prefixes for jars cannot match files, and vice versa
				String possiblePrefix = allowedResourcePrefixes.floor(elementPath);
				return possiblePrefix != null && elementPath.startsWith(possiblePrefix);
			}
		}
	}

	@VisibleForTesting
	static <E> Enumeration<E> filter(Enumeration<E> enumeration, Predicate<E> filter) {
		return new Enumeration<>() {
			private E next = null;

			@Override
			public boolean hasMoreElements() {
				if (next != null) {
					return true;
				}
				while (enumeration.hasMoreElements()) {
					E candidate = enumeration.nextElement();
					if (filter.test(candidate)) {
						next = candidate;
						break;
					}
				}
				return next != null;
			}

			@Override
			public E nextElement() {
				if (!hasMoreElements()) {
					throw new NoSuchElementException();
				}
				E result = next;
				next = null;
				return result;
			}
		};
	}
}
