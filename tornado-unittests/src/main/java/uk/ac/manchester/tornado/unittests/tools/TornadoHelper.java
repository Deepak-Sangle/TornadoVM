/*
 * Copyright (c) 2013-2020, 2022-2023, APT Group, Department of Computer Science,
 * The University of Manchester.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package uk.ac.manchester.tornado.unittests.tools;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;

import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import uk.ac.manchester.tornado.api.exceptions.TornadoDeviceFP16NotSupported;
import uk.ac.manchester.tornado.api.exceptions.TornadoDeviceFP64NotSupported;
import uk.ac.manchester.tornado.api.exceptions.TornadoDeviceFP8NotSupported;
import uk.ac.manchester.tornado.api.exceptions.TornadoDeviceMMANotSupported;
import uk.ac.manchester.tornado.api.exceptions.TornadoNoOpenCLPlatformException;
import uk.ac.manchester.tornado.unittests.common.TornadoNotSupported;
import uk.ac.manchester.tornado.unittests.common.TornadoVMMultiDeviceNotSupported;
import uk.ac.manchester.tornado.unittests.common.TornadoVMCUDANotSupported;
import uk.ac.manchester.tornado.unittests.common.TornadoVMMetalNotSupported;
import uk.ac.manchester.tornado.unittests.common.TornadoVMOpenCLNotSupported;
import uk.ac.manchester.tornado.unittests.tools.Exceptions.UnsupportedConfigurationException;

public class TornadoHelper {


    private static void printResult(Result result) {
        System.out.printf("Test ran: %s, Failed: %s%n", result.getRunCount(), result.getFailureCount());
    }

    static boolean getProperty(String property) {
        if (System.getProperty(property) != null) {
            return System.getProperty(property).toLowerCase().equals("true");
        }
        return false;
    }

    private static Method getMethodForName(Class<?> klass, String nameMethod) {
        Method method = null;
        for (Method m : klass.getMethods()) {
            if (m.getName().equals(nameMethod)) {
                method = m;
            }
        }
        return method;
    }

    /**
     * It returns the list of methods with the {@link @Test} annotation.
     */
    private static TestSuiteCollection getTestMethods(Class<?> klass) {
        Method[] methods = klass.getMethods();
        ArrayList<Method> methodsToTest = new ArrayList<>();
        HashSet<Method> unsupportedMethods = new HashSet<>();
        for (Method m : methods) {
            Annotation[] annotations = m.getAnnotations();
            boolean testEnabled = false;
            boolean ignoreTest = false;
            for (Annotation a : annotations) {
                if (a instanceof org.junit.Ignore) {
                    ignoreTest = true;
                } else if (a instanceof org.junit.Test) {
                    testEnabled = true;
                } else if (a instanceof TornadoNotSupported) {
                    testEnabled = true;
                    unsupportedMethods.add(m);
                }
            }
            if (testEnabled & !ignoreTest) {
                methodsToTest.add(m);
            }
        }
        return new TestSuiteCollection(methodsToTest, unsupportedMethods);
    }

    static void runTestVerbose(String klassName, String methodName) throws ClassNotFoundException {

        Class<?> klass = Class.forName(klassName);
        ArrayList<Method> methodsToTest = new ArrayList<>();
        TestSuiteCollection suite = null;
        if (methodName == null) {
            suite = getTestMethods(klass);
            methodsToTest = suite.methodsToTest;
        } else {
            Method method = TornadoHelper.getMethodForName(klass, methodName);
            methodsToTest.add(method);
        }

        int successCounter = 0;
        int failedCounter = 0;
        int notSupported = 0;

        // Every line is written and flushed to the console and the log file as soon as
        // it is known, instead of being buffered until the whole class finishes. If the
        // JVM is killed partway through a class (e.g. a native crash in a backend
        // driver), the results already gathered for that class stay visible instead of
        // being silently lost with it.
        try (BufferedWriter fileWriter = new BufferedWriter(new FileWriter("tornado_unittests.log", true))) {
            DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            fileWriter.write("\n" + dateFormat.format(new Date()) + "\n");
            fileWriter.flush();

            String header = "Test: " + klass + (methodName != null ? "#" + methodName : "") + "\n";
            emit(fileWriter, header);

            for (Method m : methodsToTest) {
                String runningMessage = String.format("%-50s", "\tRunning test: " + ColorsTerminal.BLUE + m.getName() + ColorsTerminal.RESET);

                if (suite != null && suite.unsupportedMethods.contains(m)) {
                    String tag = String.format("%20s", " ................ " + ColorsTerminal.YELLOW + " [NOT VALID TEST: UNSUPPORTED] " + ColorsTerminal.RESET + "\n");
                    emit(fileWriter, runningMessage + tag);
                    notSupported++;
                    continue;
                }

                Request request = Request.method(klass, m.getName());
                Result result = new JUnitCore().run(request);

                if (result.wasSuccessful()) {
                    String tag = String.format("%20s", " ................ " + ColorsTerminal.GREEN + " [PASS] " + ColorsTerminal.RESET + "\n");
                    emit(fileWriter, runningMessage + tag);
                    successCounter++;
                    continue;
                }

                // If the test did not fail but simply can't run on the current
                // configuration, one of these exceptions is set on the JUnit result.
                String unsupportedTag = unsupportedTagFor(result);
                if (unsupportedTag != null) {
                    emit(fileWriter, runningMessage + unsupportedTag);
                    notSupported++;
                    continue;
                }

                String failedTag = String.format("%20s", " ................ " + ColorsTerminal.RED + " [FAILED] " + ColorsTerminal.RESET + "\n");
                StringBuilder failureDetails = new StringBuilder(runningMessage).append(failedTag);
                failedCounter++;
                for (Failure failure : result.getFailures()) {
                    failureDetails.append("\t\t\\_[REASON] ").append(failure.getMessage()).append("\n\t").append(failure.getTrace()).append("\n").append(failure.getDescription()).append("\n").append(failure.getException());
                }
                emit(fileWriter, failureDetails.toString());
            }

            String summary = String.format("Test ran: %s, Failed: %s, Unsupported: %s%n", (successCounter + failedCounter + notSupported), failedCounter, notSupported);
            emit(fileWriter, summary);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Writes the given text to both the console and the unit-test log file, flushing both
     * immediately so the text survives even if the JVM is killed right after this call returns.
     */
    private static void emit(BufferedWriter fileWriter, String text) throws IOException {
        System.out.print(text);
        System.out.flush();
        fileWriter.write(text);
        fileWriter.flush();
    }

    /**
     * Returns the console tag for a JUnit failure that is actually an expected
     * "not supported on this configuration" outcome, or {@code null} if the
     * failure is a genuine test failure.
     */
    private static String unsupportedTagFor(Result result) {
        if (result.getFailures().stream().anyMatch(e -> (e.getException() instanceof UnsupportedConfigurationException))) {
            return String.format("%20s", " ................ " + ColorsTerminal.PURPLE + " [UNSUPPORTED CONFIGURATION: At least 2 accelerators are required] " + ColorsTerminal.RESET + "\n");
        }
        if (result.getFailures().stream().anyMatch(e -> (e.getException() instanceof TornadoNoOpenCLPlatformException))) {
            return String.format("%20s", " ................ " + ColorsTerminal.PURPLE + " [OPENCL CONFIGURATION UNSUPPORTED] " + ColorsTerminal.RESET + "\n");
        }
        if (result.getFailures().stream().anyMatch(e -> (e.getException() instanceof TornadoVMMultiDeviceNotSupported))) {
            return String.format("%20s", " ................ " + ColorsTerminal.PURPLE + " [[UNSUPPORTED] MULTI-DEVICE CONFIGURATION REQUIRED] " + ColorsTerminal.RESET + "\n");
        }
        if (result.getFailures().stream().anyMatch(e -> (e.getException() instanceof TornadoVMOpenCLNotSupported))) {
            return String.format("%20s", " ................ " + ColorsTerminal.PURPLE + " [OPENCL CONFIGURATION UNSUPPORTED] " + ColorsTerminal.RESET + "\n");
        }
        if (result.getFailures().stream().anyMatch(e -> (e.getException() instanceof TornadoVMMetalNotSupported))) {
            return String.format("%20s", " ................ " + ColorsTerminal.PURPLE + " [METAL CONFIGURATION UNSUPPORTED] " + ColorsTerminal.RESET + "\n");
        }
        if (result.getFailures().stream().anyMatch(e -> (e.getException() instanceof TornadoVMCUDANotSupported))) {
            return String.format("%20s", " ................ " + ColorsTerminal.PURPLE + " [CUDA CONFIGURATION UNSUPPORTED] " + ColorsTerminal.RESET + "\n");
        }
        if (result.getFailures().stream().anyMatch(e -> (e.getException() instanceof TornadoDeviceFP64NotSupported))) {
            return String.format("%20s", " ................ " + ColorsTerminal.YELLOW + " [FP64 UNSUPPORTED FOR CURRENT DEVICE] " + ColorsTerminal.RESET + "\n");
        }
        if (result.getFailures().stream().anyMatch(e -> (e.getException() instanceof TornadoDeviceFP16NotSupported))) {
            return String.format("%20s", " ................ " + ColorsTerminal.YELLOW + " [FP16 UNSUPPORTED FOR CURRENT DEVICE] " + ColorsTerminal.RESET + "\n");
        }
        if (result.getFailures().stream().anyMatch(e -> (e.getException() instanceof TornadoDeviceMMANotSupported))) {
            return String.format("%20s", " ................ " + ColorsTerminal.YELLOW + " [MMA UNSUPPORTED FOR CURRENT DEVICE] " + ColorsTerminal.RESET + "\n");
        }
        if (result.getFailures().stream().anyMatch(e -> (e.getException() instanceof TornadoDeviceFP8NotSupported))) {
            return String.format("%20s", " ................ " + ColorsTerminal.YELLOW + " [FP8 UNSUPPORTED FOR CURRENT DEVICE] " + ColorsTerminal.RESET + "\n");
        }
        return null;
    }

    static void runTestClassAndMethod(String klassName, String methodName) throws ClassNotFoundException {
        Request request = Request.method(Class.forName(klassName), methodName);
        Result result = new JUnitCore().run(request);
        printResult(result);
    }

    static void runTestClass(String klassName) throws ClassNotFoundException {
        Request request = Request.aClass(Class.forName(klassName));
        Result result = new JUnitCore().run(request);
        printResult(result);
    }

    static class TestSuiteCollection {
        ArrayList<Method> methodsToTest;
        HashSet<Method> unsupportedMethods;

        TestSuiteCollection(ArrayList<Method> methodsToTest, HashSet<Method> unsupportedMethods) {
            this.methodsToTest = methodsToTest;
            this.unsupportedMethods = unsupportedMethods;
        }
    }
}
