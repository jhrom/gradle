/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.classpath;

import kotlin.io.FilesKt;
import org.codehaus.groovy.runtime.ProcessGroovyMethods;
import org.codehaus.groovy.runtime.callsite.CallSite;
import org.codehaus.groovy.runtime.callsite.CallSiteArray;
import org.codehaus.groovy.vmplugin.v8.IndyInterface;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.classpath.intercept.CallInterceptor;
import org.gradle.internal.classpath.intercept.CallInterceptorsSet;
import org.gradle.internal.classpath.intercept.ClassBoundCallInterceptor;
import org.gradle.internal.classpath.intercept.InterceptScope;
import org.gradle.internal.classpath.intercept.Invocation;
import org.gradle.internal.lazy.Lazy;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.gradle.internal.classpath.InstrumentedUtil.findStaticOrThrowError;
import static org.gradle.internal.classpath.InstrumentedUtil.kotlinDefaultMethodType;

public class Instrumented {
    private static final Listener NO_OP = new Listener() {
        @Override
        public void systemPropertyQueried(String key, @Nullable Object value, String consumer) {
        }

        @Override
        public void systemPropertyChanged(Object key, @Nullable Object value, String consumer) {
        }

        @Override
        public void systemPropertyRemoved(Object key, String consumer) {
        }

        @Override
        public void systemPropertiesCleared(String consumer) {
        }

        @Override
        public void envVariableQueried(String key, @Nullable String value, String consumer) {
        }

        @Override
        public void externalProcessStarted(String command, String consumer) {
        }

        @Override
        public void fileOpened(File file, String consumer) {
        }

        @Override
        public void fileObserved(File file, String consumer) {
        }

        @Override
        public void fileSystemEntryObserved(File file, String consumer) {
        }

        @Override
        public void directoryContentObserved(File file, String consumer) {
        }
    };

    private static final AtomicReference<Listener> LISTENER = new AtomicReference<>(NO_OP);

    public static void setListener(Listener listener) {
        LISTENER.set(listener);
    }

    public static void discardListener() {
        setListener(NO_OP);
    }

    private static final CallInterceptorsSet CALL_INTERCEPTORS = new CallInterceptorsSet(
        new SystemGetPropertyInterceptor(),
        new SystemSetPropertyInterceptor(),
        new SystemGetPropertiesInterceptor(),
        new SystemSetPropertiesInterceptor(),
        new SystemClearPropertyInterceptor(),
        new IntegerGetIntegerInterceptor(),
        new LongGetLongInterceptor(),
        new BooleanGetBooleanInterceptor(),
        new SystemGetenvInterceptor(),
        new RuntimeExecInterceptor(),
        new FileCheckInterceptor.FileExistsInterceptor(),
        new FileCheckInterceptor.FileIsFileInterceptor(),
        new FileCheckInterceptor.FileIsDirectoryInterceptor(),
        new FileListFilesInterceptor(),
        new FilesReadStringInterceptor(),
        new ProcessGroovyMethodsExecuteInterceptor(),
        new ProcessBuilderStartInterceptor(),
        new ProcessBuilderStartPipelineInterceptor(),
        new FileInputStreamConstructorInterceptor());


    // Called by generated code
    @SuppressWarnings("unused")
    public static void groovyCallSites(CallSiteArray array) {
        for (CallSite callSite : array.array) {
            array.array[callSite.getIndex()] = CALL_INTERCEPTORS.maybeDecorateGroovyCallSite(callSite);
        }
    }

    /**
     * The bootstrap method for method calls from Groovy compiled code with indy enabled.
     * Gradle's bytecode processor replaces the Groovy's original {@link IndyInterface#bootstrap(MethodHandles.Lookup, String, MethodType, String, int)}
     * with this method to intercept potentially "interesting" calls and do some additional work.
     *
     * @param caller the lookup for the caller (JVM-supplied)
     * @param callType the type of the call (corresponds to {@link IndyInterface.CallType} constant)
     * @param type the call site type
     * @param name the real method name
     * @param flags call flags
     * @return the produced CallSite
     * @see IndyInterface
     */
    public static java.lang.invoke.CallSite bootstrap(MethodHandles.Lookup caller, String callType, MethodType type, String name, int flags) {
        return CALL_INTERCEPTORS.maybeDecorateIndyCallSite(
            IndyInterface.bootstrap(caller, callType, type, name, flags), caller, callType, name, flags);
    }

    // Called by generated code.
    public static String systemProperty(String key, String consumer) {
        return systemProperty(key, null, consumer);
    }

    // Called by generated code.
    public static String systemProperty(String key, @Nullable String defaultValue, String consumer) {
        String value = System.getProperty(key);
        systemPropertyQueried(key, value, consumer);
        return value == null ? defaultValue : value;
    }

    // Called by generated code.
    public static Properties systemProperties(String consumer) {
        return new AccessTrackingProperties(System.getProperties(), new AccessTrackingProperties.Listener() {
            // Do not track accesses to non-String properties. Only String properties can be set externally, so they cannot affect the cached configuration.
            @Override
            public void onAccess(Object key, @Nullable Object value) {
                if (key instanceof String && (value == null || value instanceof String)) {
                    systemPropertyQueried(convertToString(key), convertToString(value), consumer);
                }
            }

            @Override
            public void onChange(Object key, Object newValue) {
                listener().systemPropertyChanged(key, newValue, consumer);
            }

            @Override
            public void onRemove(Object key) {
                listener().systemPropertyRemoved(key, consumer);
            }

            @Override
            public void onClear() {
                listener().systemPropertiesCleared(consumer);
            }
        });
    }

    // Called by generated code.
    public static String setSystemProperty(String key, String value, String consumer) {
        String oldValue = System.setProperty(key, value);
        systemPropertyQueried(key, oldValue, consumer);
        listener().systemPropertyChanged(key, value, consumer);
        return oldValue;
    }

    // Called by generated code.
    public static String clearSystemProperty(String key, String consumer) {
        String oldValue = System.clearProperty(key);
        systemPropertyQueried(key, oldValue, consumer);
        listener().systemPropertyRemoved(key, consumer);
        return oldValue;
    }

    public static void setSystemProperties(Properties properties, String consumer) {
        listener().systemPropertiesCleared(consumer);
        properties.forEach((k, v) -> listener().systemPropertyChanged(k, v, consumer));
        System.setProperties(properties);
    }

    // Called by generated code.
    public static Integer getInteger(String key, String consumer) {
        systemPropertyQueried(key, consumer);
        return Integer.getInteger(key);
    }

    // Called by generated code.
    public static Integer getInteger(String key, int defaultValue, String consumer) {
        systemPropertyQueried(key, consumer);
        return Integer.getInteger(key, defaultValue);
    }

    // Called by generated code.
    public static Integer getInteger(String key, Integer defaultValue, String consumer) {
        systemPropertyQueried(key, consumer);
        return Integer.getInteger(key, defaultValue);
    }

    // Called by generated code.
    public static Long getLong(String key, String consumer) {
        systemPropertyQueried(key, consumer);
        return Long.getLong(key);
    }

    // Called by generated code.
    public static Long getLong(String key, long defaultValue, String consumer) {
        systemPropertyQueried(key, consumer);
        return Long.getLong(key, defaultValue);
    }

    // Called by generated code.
    public static Long getLong(String key, Long defaultValue, String consumer) {
        systemPropertyQueried(key, consumer);
        return Long.getLong(key, defaultValue);
    }

    // Called by generated code.
    public static boolean getBoolean(String key, String consumer) {
        systemPropertyQueried(key, consumer);
        return Boolean.getBoolean(key);
    }

    // Called by generated code.
    public static String getenv(String key, String consumer) {
        String value = System.getenv(key);
        envVariableQueried(key, value, consumer);
        return value;
    }

    // Called by generated code.
    public static Map<String, String> getenv(String consumer) {
        return new AccessTrackingEnvMap((key, value) -> envVariableQueried(convertToString(key), value, consumer));
    }

    public static Process exec(Runtime runtime, String command, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return runtime.exec(command);
    }

    public static Process exec(Runtime runtime, String[] command, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return runtime.exec(command);
    }

    public static Process exec(Runtime runtime, String command, String[] envp, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return runtime.exec(command, envp);
    }

    public static Process exec(Runtime runtime, String[] command, String[] envp, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return runtime.exec(command, envp);
    }

    public static Process exec(Runtime runtime, String command, String[] envp, File dir, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return runtime.exec(command, envp, dir);
    }

    public static Process exec(Runtime runtime, String[] command, String[] envp, File dir, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return runtime.exec(command, envp, dir);
    }

    public static Process execute(String command, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return ProcessGroovyMethods.execute(command);
    }

    public static Process execute(String[] command, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return ProcessGroovyMethods.execute(command);
    }

    public static Process execute(List<?> command, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return ProcessGroovyMethods.execute(command);
    }

    public static Process execute(String command, String[] envp, File dir, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return ProcessGroovyMethods.execute(command, envp, dir);
    }

    public static Process execute(String command, List<?> envp, File dir, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return ProcessGroovyMethods.execute(command, envp, dir);
    }

    public static Process execute(String[] command, String[] envp, File dir, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return ProcessGroovyMethods.execute(command, envp, dir);
    }

    public static Process execute(String[] command, List<?> envp, File dir, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return ProcessGroovyMethods.execute(command, envp, dir);
    }

    public static Process execute(List<?> command, String[] envp, File dir, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return ProcessGroovyMethods.execute(command, envp, dir);
    }

    public static Process execute(List<?> command, List<?> envp, File dir, String consumer) throws IOException {
        externalProcessStarted(command, consumer);
        return ProcessGroovyMethods.execute(command, envp, dir);
    }

    public static Process start(ProcessBuilder builder, String consumer) throws IOException {
        externalProcessStarted(builder.command(), consumer);
        return builder.start();
    }

    public static boolean fileExists(File file, String consumer) {
        listener().fileSystemEntryObserved(file, consumer);
        return file.exists();
    }

    public static boolean fileIsFile(File file, String consumer) {
        listener().fileSystemEntryObserved(file, consumer);
        return file.isFile();
    }

    public static boolean fileIsDirectory(File file, String consumer) {
        listener().fileSystemEntryObserved(file, consumer);
        return file.isDirectory();
    }

    public static File[] fileListFiles(File file, String consumer) {
        listener().directoryContentObserved(file, consumer);
        return file.listFiles();
    }

    public static File[] fileListFiles(File file, FileFilter fileFilter, String consumer) {
        listener().directoryContentObserved(file, consumer);
        return file.listFiles(fileFilter);
    }

    public static File[] fileListFiles(File file, FilenameFilter fileFilter, String consumer) {
        listener().directoryContentObserved(file, consumer);
        return file.listFiles(fileFilter);
    }

    public static String kotlinIoFilesKtReadText(File receiver, Charset charset, String consumer) {
        listener().fileOpened(receiver, consumer);
        return FilesKt.readText(receiver, charset);
    }

    public static String kotlinIoFilesKtReadTextDefault(File receiver, Charset charset, int defaultMask, Object defaultMarker, String consumer) throws Throwable {
        listener().fileOpened(receiver, consumer);
        return (String) FILESKT_READ_TEXT_DEFAULT.invokeExact(receiver, charset, defaultMask, defaultMarker);
    }

    private static final MethodHandle FILESKT_READ_TEXT_DEFAULT = findStaticOrThrowError(FilesKt.class, "readText$default", kotlinDefaultMethodType(String.class, File.class, Charset.class));

    public static String filesReadString(Path file, String consumer) throws Throwable {
        listener().fileOpened(file.toFile(), consumer);
        return (String) FILES_READ_STRING_PATH.get().invokeExact(file);
    }

    public static String filesReadString(Path file, Charset charset, String consumer) throws Throwable {
        listener().fileOpened(file.toFile(), consumer);
        return (String) FILES_READ_STRING_PATH_CHARSET.get().invokeExact(file, charset);
    }

    // These are initialized lazily, as we may be running a Java version < 11 which does not have the APIs.
    private static final Lazy<MethodHandle> FILES_READ_STRING_PATH =
        Lazy.locking().of(() -> findStaticOrThrowError(Files.class, "readString", MethodType.methodType(String.class, Path.class)));
    private static final Lazy<MethodHandle> FILES_READ_STRING_PATH_CHARSET =
        Lazy.locking().of(() -> findStaticOrThrowError(Files.class, "readString", MethodType.methodType(String.class, Path.class, Charset.class)));

    @SuppressWarnings("unchecked")
    public static List<Process> startPipeline(List<ProcessBuilder> pipeline, String consumer) throws IOException {
        try {
            for (ProcessBuilder builder : pipeline) {
                externalProcessStarted(builder.command(), consumer);
            }
            Object result = ProcessBuilder.class.getMethod("startPipeline", List.class).invoke(null, pipeline);
            return (List<Process>) result;
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new NoSuchMethodError("Cannot find method ProcessBuilder.startPipeline");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else if (cause instanceof Error) {
                throw (Error) cause;
            } else {
                throw new RuntimeException("Unexpected exception thrown by ProcessBuilder.startPipeline", e);
            }
        }
    }

    public static void fileObserved(File file, String consumer) {
        listener().fileObserved(absoluteFileOf(file), consumer);
    }

    public static void fileOpened(File file, String consumer) {
        listener().fileOpened(absoluteFileOf(file), consumer);
    }

    private static File absoluteFileOf(File file) {
        return file.isAbsolute() ? file : new File(currentDir(), file.getPath());
    }

    private static File currentDir() {
        return SystemProperties.getInstance().getCurrentDir();
    }

    public static void fileOpened(String path, String consumer) {
        fileOpened(new File(path), consumer);
    }

    private static void envVariableQueried(String key, String value, String consumer) {
        listener().envVariableQueried(key, value, consumer);
    }

    private static void systemPropertyQueried(String key, String consumer) {
        systemPropertyQueried(key, System.getProperty(key), consumer);
    }

    private static void systemPropertyQueried(String key, @Nullable String value, String consumer) {
        listener().systemPropertyQueried(key, value, consumer);
    }

    private static void externalProcessStarted(String command, String consumer) {
        listener().externalProcessStarted(command, consumer);
    }

    private static void externalProcessStarted(String[] command, String consumer) {
        externalProcessStarted(joinCommand(command), consumer);
    }

    private static void externalProcessStarted(List<?> command, String consumer) {
        externalProcessStarted(joinCommand(command), consumer);
    }

    private static Listener listener() {
        return LISTENER.get();
    }

    private static String convertToString(Object arg) {
        if (arg instanceof CharSequence) {
            return ((CharSequence) arg).toString();
        }
        return (String) arg;
    }

    private static String joinCommand(String[] command) {
        return String.join(" ", command);
    }

    private static String joinCommand(List<?> command) {
        return command.stream().map(String::valueOf).collect(Collectors.joining(" "));
    }

    public interface Listener {
        /**
         * Invoked when the code reads the system property with the String key.
         *
         * @param key the name of the property
         * @param value the value of the property at the time of reading or {@code null} if the property is not present
         * @param consumer the name of the class that is reading the property value
         */
        void systemPropertyQueried(String key, @Nullable Object value, String consumer);

        /**
         * Invoked when the code updates or adds the system property.
         *
         * @param key the name of the property, can be non-string
         * @param value the new value of the property, can be {@code null} or non-string
         * @param consumer the name of the class that is updating the property value
         */
        void systemPropertyChanged(Object key, @Nullable Object value, String consumer);

        /**
         * Invoked when the code removes the system property. The property may not be present.
         *
         * @param key the name of the property, can be non-string
         * @param consumer the name of the class that is removing the property value
         */
        void systemPropertyRemoved(Object key, String consumer);

        /**
         * Invoked when all system properties are removed.
         *
         * @param consumer the name of the class that is removing the system properties
         */
        void systemPropertiesCleared(String consumer);

        /**
         * Invoked when the code reads the environment variable.
         *
         * @param key the name of the variable
         * @param value the value of the variable
         * @param consumer the name of the class that is reading the variable
         */
        void envVariableQueried(String key, @Nullable String value, String consumer);

        /**
         * Invoked when the code starts an external process. The command string with all argument is provided for reporting but its value may not be suitable to actually invoke the command because all
         * arguments are joined together (separated by space) and there is no escaping of special characters.
         *
         * @param command the command used to start the process (with arguments)
         * @param consumer the name of the class that is starting the process
         */
        void externalProcessStarted(String command, String consumer);

        /**
         * Invoked when the code opens a file.
         *
         * @param file the absolute file that was open
         * @param consumer the name of the class that is opening the file
         */
        void fileOpened(File file, String consumer);

        void fileObserved(File file, String consumer);

        void fileSystemEntryObserved(File file, String consumer);

        void directoryContentObserved(File file, String consumer);
    }

    /**
     * The interceptor for {@link Integer#getInteger(String)}, {@link Integer#getInteger(String, int)}, and {@link Integer#getInteger(String, Integer)}.
     */
    private static class IntegerGetIntegerInterceptor extends ClassBoundCallInterceptor {
        public IntegerGetIntegerInterceptor() {
            super(Integer.class, InterceptScope.methodsNamed("getInteger"));
        }

        @Override
        protected Object doInterceptSafe(Invocation invocation, String consumer) throws Throwable {
            switch (invocation.getArgsCount()) {
                case 1:
                    return getInteger(invocation.getArgument(0).toString(), consumer);
                case 2:
                    return getInteger(invocation.getArgument(0).toString(), (Integer) invocation.getArgument(1), consumer);
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for {@link Long#getLong(String)}, {@link Long#getLong(String, long)}, and {@link Long#getLong(String, Long)}.
     */
    private static class LongGetLongInterceptor extends ClassBoundCallInterceptor {
        public LongGetLongInterceptor() {
            super(Long.class, InterceptScope.methodsNamed("getLong"));
        }

        @Override
        protected Object doInterceptSafe(Invocation invocation, String consumer) throws Throwable {
            switch (invocation.getArgsCount()) {
                case 1:
                    return getLong(invocation.getArgument(0).toString(), consumer);
                case 2:
                    return getLong(invocation.getArgument(0).toString(), (Long) invocation.getArgument(1), consumer);
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for {@link Boolean#getBoolean(String)}.
     */
    private static class BooleanGetBooleanInterceptor extends ClassBoundCallInterceptor {
        public BooleanGetBooleanInterceptor() {
            super(Boolean.class, InterceptScope.methodsNamed("getBoolean"));
        }

        @Override
        protected Object doInterceptSafe(Invocation invocation, String consumer) throws Throwable {
            if (invocation.getArgsCount() == 1) {
                return getBoolean(invocation.getArgument(0).toString(), consumer);
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for {@link System#getProperty(String)} and {@link System#getProperty(String, String)}.
     */
    private static class SystemGetPropertyInterceptor extends ClassBoundCallInterceptor {
        public SystemGetPropertyInterceptor() {
            super(System.class, InterceptScope.methodsNamed("getProperty"));
        }

        @Override
        protected Object doInterceptSafe(Invocation invocation, String consumer) throws Throwable {
            switch (invocation.getArgsCount()) {
                case 1:
                    return systemProperty(invocation.getArgument(0).toString(), consumer);
                case 2:
                    return systemProperty(invocation.getArgument(0).toString(), convertToString(invocation.getArgument(1)), consumer);
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for {@link System#setProperty(String, String)}.
     */
    private static class SystemSetPropertyInterceptor extends ClassBoundCallInterceptor {
        public SystemSetPropertyInterceptor() {
            super(System.class, InterceptScope.methodsNamed("setProperty"));
        }

        @Override
        protected Object doInterceptSafe(Invocation invocation, String consumer) throws Throwable {
            if (invocation.getArgsCount() == 2) {
                return setSystemProperty(convertToString(invocation.getArgument(0)), convertToString(invocation.getArgument(1)), consumer);
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for {@link System#getProperties()} and {@code System.properties} reads.
     */
    private static class SystemGetPropertiesInterceptor extends ClassBoundCallInterceptor {
        public SystemGetPropertiesInterceptor() {
            super(System.class,
                InterceptScope.readsOfPropertiesNamed("properties"),
                InterceptScope.methodsNamed("getProperties"));
        }

        @Override
        protected Object doInterceptSafe(Invocation invocation, String consumer) throws Throwable {
            if (invocation.getArgsCount() == 0) {
                return systemProperties(consumer);
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for {@link System#setProperties(Properties)}.
     */
    private static class SystemSetPropertiesInterceptor extends ClassBoundCallInterceptor {
        public SystemSetPropertiesInterceptor() {
            super(System.class, InterceptScope.methodsNamed("setProperties"));
        }

        @Override
        protected Object doInterceptSafe(Invocation invocation, String consumer) throws Throwable {
            if (invocation.getArgsCount() == 1) {
                setSystemProperties((Properties) invocation.getArgument(0), consumer);
                return null;
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for {@link System#clearProperty(String)}.
     */
    private static class SystemClearPropertyInterceptor extends ClassBoundCallInterceptor {
        public SystemClearPropertyInterceptor() {
            super(System.class, InterceptScope.methodsNamed("clearProperty"));
        }

        @Override
        protected Object doInterceptSafe(Invocation invocation, String consumer) throws Throwable {
            if (invocation.getArgsCount() == 1) {
                return clearSystemProperty(convertToString(invocation.getArgument(0)), consumer);
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for {@link System#getenv()} and {@link System#getenv(String)}.
     */
    private static class SystemGetenvInterceptor extends ClassBoundCallInterceptor {
        public SystemGetenvInterceptor() {
            super(System.class, InterceptScope.methodsNamed("getenv"));
        }

        @Override
        protected Object doInterceptSafe(Invocation invocation, String consumer) throws Throwable {
            switch (invocation.getArgsCount()) {
                case 0:
                    return getenv(consumer);
                case 1:
                    return getenv(convertToString(invocation.getArgument(0)), consumer);
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for all overloads of {@code Runtime.exec}.
     */
    private static class RuntimeExecInterceptor extends CallInterceptor {
        public RuntimeExecInterceptor() {
            super(InterceptScope.methodsNamed("exec"));
        }

        @Override
        protected Object doIntercept(Invocation invocation, String consumer) throws Throwable {
            int argsCount = invocation.getArgsCount();
            if (1 <= argsCount && argsCount <= 3) {
                Optional<Process> result = tryCallExec(invocation.getReceiver(), invocation.getArgument(0), invocation.getOptionalArgument(1), invocation.getOptionalArgument(2), consumer);
                if (result.isPresent()) {
                    return result.get();
                }
            }
            return invocation.callOriginal();
        }

        private Optional<Process> tryCallExec(Object runtimeArg, Object commandArg, @Nullable Object envpArg, @Nullable Object fileArg, String consumer) throws Throwable {
            if (runtimeArg instanceof Runtime) {
                Runtime runtime = (Runtime) runtimeArg;

                if (fileArg == null || fileArg instanceof File) {
                    File file = (File) fileArg;

                    if (envpArg == null || envpArg instanceof String[]) {
                        String[] envp = (String[]) envpArg;

                        if (commandArg instanceof CharSequence) {
                            String command = convertToString(commandArg);
                            return Optional.of(exec(runtime, command, envp, file, consumer));
                        } else if (commandArg instanceof String[]) {
                            String[] command = (String[]) commandArg;
                            return Optional.of(exec(runtime, command, envp, file, consumer));
                        }
                    }
                }
            }
            return Optional.empty();
        }
    }

    private static abstract class FileCheckInterceptor extends CallInterceptor {
        private final BiFunction<File, String, Boolean> invokeWhenIntercepted;

        public FileCheckInterceptor(String methodName, BiFunction<File, String, Boolean> invokeWhenIntercepted) {
            super(InterceptScope.methodsNamed(methodName));
            this.invokeWhenIntercepted = invokeWhenIntercepted;
        }

        @Override
        protected Object doIntercept(Invocation invocation, String consumer) throws Throwable {
            if (invocation.getArgsCount() == 0) {
                Object receiver = invocation.getReceiver();
                if (receiver instanceof File) {
                    File fileReceiver = (File) receiver;
                    return invokeWhenIntercepted.apply(fileReceiver, consumer);
                }
            }
            return invocation.callOriginal();
        }

        static class FileExistsInterceptor extends FileCheckInterceptor {
            public FileExistsInterceptor() {
                super("exists", Instrumented::fileExists);
            }
        }

        static class FileIsFileInterceptor extends FileCheckInterceptor {
            public FileIsFileInterceptor() {
                super("isFile", Instrumented::fileIsFile);
            }
        }

        static class FileIsDirectoryInterceptor extends FileCheckInterceptor {
            public FileIsDirectoryInterceptor() {
                super("isDirectory", Instrumented::fileIsDirectory);
            }
        }
    }

    private static class FileListFilesInterceptor extends CallInterceptor {
        public FileListFilesInterceptor() {
            super(InterceptScope.methodsNamed("listFiles"));
        }

        @Override
        protected Object doIntercept(Invocation invocation, String consumer) throws Throwable {
            if (invocation.getArgsCount() <= 1) {
                Object receiver = invocation.getReceiver();
                if (receiver instanceof File) {
                    File fileReceiver = (File) receiver;
                    if (invocation.getArgsCount() == 0) {
                        return Instrumented.fileListFiles(fileReceiver, consumer);
                    } else if (invocation.getArgsCount() == 1) {
                        Object arg0 = invocation.getArgument(0);
                        if (arg0 instanceof FileFilter) {
                            return Instrumented.fileListFiles(fileReceiver, (FileFilter) arg0, consumer);
                        } else if (arg0 instanceof FilenameFilter) {
                            return Instrumented.fileListFiles(fileReceiver, (FilenameFilter) arg0, consumer);
                        }
                    }
                }
            }
            return invocation.callOriginal();
        }
    }

    private static class FilesReadStringInterceptor extends ClassBoundCallInterceptor {
        public FilesReadStringInterceptor() {
            super(Files.class, InterceptScope.methodsNamed("readString"));
        }

        @Override
        protected Object doInterceptSafe(Invocation invocation, String consumer) throws Throwable {
            if (invocation.getArgsCount() >= 1 && invocation.getArgsCount() <= 2) {
                Object arg0 = invocation.getArgument(0);
                if (arg0 instanceof Path) {
                    Path arg0Path = (Path) arg0;
                    if (invocation.getArgsCount() == 1) {
                        return Instrumented.filesReadString(arg0Path, consumer);
                    } else if (invocation.getArgsCount() == 2) {
                        Object arg1 = invocation.getArgument(1);
                        if (arg1 instanceof Charset) {
                            Charset arg1Charset = (Charset) arg1;
                            return Instrumented.filesReadString(arg0Path, arg1Charset, consumer);
                        }
                    }
                }
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for Groovy's {@code String.execute}, {@code String[].execute}, and {@code List.execute}. This also handles {@code ProcessGroovyMethods.execute}.
     */
    private static class ProcessGroovyMethodsExecuteInterceptor extends CallInterceptor {
        protected ProcessGroovyMethodsExecuteInterceptor() {
            super(InterceptScope.methodsNamed("execute"));
        }

        @Override
        protected Object doIntercept(Invocation invocation, String consumer) throws Throwable {
            // Static calls have Class<ProcessGroovyMethods> as a receiver, command as a first argument, optional arguments follow.
            // "Extension" calls have command as a receiver and optional arguments as arguments.
            boolean isStaticCall = invocation.getReceiver().equals(ProcessGroovyMethods.class);
            int argsCount = invocation.getArgsCount();
            // Offset accounts for the command being in the list of arguments.
            int nonCommandArgsOffset = isStaticCall ? 1 : 0;
            int nonCommandArgsCount = argsCount - nonCommandArgsOffset;

            if (nonCommandArgsCount != 0 && nonCommandArgsCount != 2) {
                // This is an unsupported overload, skip interception.
                return invocation.callOriginal();
            }

            Object commandArg = isStaticCall ? invocation.getArgument(0) : invocation.getReceiver();
            Object envpArg = invocation.getOptionalArgument(nonCommandArgsOffset);
            Object fileArg = invocation.getOptionalArgument(nonCommandArgsOffset + 1);
            Optional<Process> result = tryCallExecute(commandArg, envpArg, fileArg, consumer);
            if (result.isPresent()) {
                return result.get();
            }
            return invocation.callOriginal();
        }

        private Optional<Process> tryCallExecute(Object commandArg, @Nullable Object envpArg, @Nullable Object fileArg, String consumer) throws Throwable {
            if (fileArg == null || fileArg instanceof File) {
                File file = (File) fileArg;

                if (commandArg instanceof CharSequence) {
                    String command = convertToString(commandArg);

                    if (envpArg == null || envpArg instanceof String[]) {
                        return Optional.of(execute(command, (String[]) envpArg, file, consumer));
                    } else if (envpArg instanceof List) {
                        return Optional.of(execute(command, (List<?>) envpArg, file, consumer));
                    }
                } else if (commandArg instanceof String[]) {
                    String[] command = (String[]) commandArg;

                    if (envpArg == null || envpArg instanceof String[]) {
                        return Optional.of(execute(command, (String[]) envpArg, file, consumer));
                    } else if (envpArg instanceof List) {
                        return Optional.of(execute(command, (List<?>) envpArg, file, consumer));
                    }
                } else if (commandArg instanceof List) {
                    List<?> command = (List<?>) commandArg;

                    if (envpArg == null || envpArg instanceof String[]) {
                        return Optional.of(execute(command, (String[]) envpArg, file, consumer));
                    } else if (envpArg instanceof List) {
                        return Optional.of(execute(command, (List<?>) envpArg, file, consumer));
                    }
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The interceptor for {@link ProcessBuilder#start()}.
     */
    private static class ProcessBuilderStartInterceptor extends CallInterceptor {
        ProcessBuilderStartInterceptor() {
            super(InterceptScope.methodsNamed("start"));
        }

        @Override
        protected Object doIntercept(Invocation invocation, String consumer) throws Throwable {
            Object receiver = invocation.getReceiver();
            if (receiver instanceof ProcessBuilder) {
                return start((ProcessBuilder) receiver, consumer);
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for {@code ProcessBuilder.startPipeline(List)}.
     */
    private static class ProcessBuilderStartPipelineInterceptor extends ClassBoundCallInterceptor {
        public ProcessBuilderStartPipelineInterceptor() {
            super(ProcessBuilder.class, InterceptScope.methodsNamed("startPipeline"));
        }

        @SuppressWarnings("unchecked")
        @Override
        protected Object doInterceptSafe(Invocation invocation, String consumer) throws Throwable {
            if (invocation.getArgsCount() == 1 && invocation.getArgument(0) instanceof List) {
                return startPipeline((List<ProcessBuilder>) invocation.getArgument(0), consumer);
            }
            return invocation.callOriginal();
        }
    }

    /**
     * The interceptor for {@link FileInputStream#FileInputStream(File)} and {@link FileInputStream#FileInputStream(String)}.
     */
    private static class FileInputStreamConstructorInterceptor extends CallInterceptor {
        public FileInputStreamConstructorInterceptor() {
            super(InterceptScope.constructorsOf(FileInputStream.class));
        }

        @Override
        protected Object doIntercept(Invocation invocation, String consumer) throws Throwable {
            if (invocation.getArgsCount() == 1) {
                Object argument = invocation.getArgument(0);
                if (argument instanceof CharSequence) {
                    String path = convertToString(argument);
                    fileOpened(path, consumer);
                    return new FileInputStream(path);
                } else if (argument instanceof File) {
                    File file = (File) argument;
                    fileOpened(file, consumer);
                    return new FileInputStream(file);
                }
            }
            return invocation.callOriginal();

        }
    }
}

class InstrumentedUtil {
    static MethodHandle findStaticOrThrowError(Class<?> refc, String name, MethodType methodType) {
        try {
            return MethodHandles.lookup().findStatic(refc, name, methodType);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(e.getMessage());
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    static MethodType kotlinDefaultMethodType(Class<?> returnType, Class<?>... parameterTypes) {
        Class<?>[] defaultParameterTypes = Arrays.copyOf(parameterTypes, parameterTypes.length + 2);
        defaultParameterTypes[parameterTypes.length] = int.class; // default value mask
        defaultParameterTypes[parameterTypes.length + 1] = Object.class; // default signature marker
        return MethodType.methodType(returnType, defaultParameterTypes);
    }
}

