/**
 * Copyright 2011-2012 the original author or authors.
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
 */
package org.informantproject.testkit;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.MainEntryPoint;
import org.informantproject.testkit.SocketCommander.CommandWrapper;
import org.informantproject.testkit.SocketCommander.ResponseWrapper;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class SocketCommandProcessor implements Runnable {

    public static final String EXECUTE_APP_COMMAND = "EXECUTE_APP";
    public static final String GET_PORT_COMMAND = "GET_PORT";
    public static final String EXCEPTION_RESPONSE = "EXCEPTION";

    private static final Logger logger = LoggerFactory.getLogger(SocketCommandProcessor.class);

    private final ObjectInputStream objectIn;
    private final ObjectOutputStream objectOut;

    SocketCommandProcessor(ObjectInputStream objectIn, ObjectOutputStream objectOut) {
        this.objectIn = objectIn;
        this.objectOut = objectOut;
    }

    public void run() {
        try {
            runInternal();
        } catch (EOFException e) {
            // socket was closed, terminate gracefully
            System.exit(0);
        } catch (Throwable e) {
            // this may not get logged if test jvm has been terminated already
            logger.error(e.getMessage(), e);
            System.exit(0);
        }
    }

    private void runInternal() throws Exception {
        while (true) {
            CommandWrapper commandWrapper = (CommandWrapper) objectIn.readObject();
            Object command = commandWrapper.getCommand();
            int commandNum = commandWrapper.getCommandNum();
            if (command instanceof String) {
                if (command.equals(GET_PORT_COMMAND)) {
                    respond(MainEntryPoint.getPort(), commandNum);
                } else {
                    logger.error("unexpected command '" + command + "'");
                    respond(EXCEPTION_RESPONSE, commandNum);
                }
            } else if (command instanceof List) {
                List<?> argList = (List<?>) command;
                if (argList.isEmpty()) {
                    logger.error("unexpected empty command");
                    respond(EXCEPTION_RESPONSE, commandNum);
                } else {
                    Object commandName = argList.get(0);
                    if (commandName.equals(EXECUTE_APP_COMMAND)) {
                        String appClassName = (String) argList.get(1);
                        String threadName = (String) argList.get(2);
                        executeAppAndRespond(appClassName, threadName, commandNum);
                    } else {
                        logger.error("unexpected command '" + commandName + "'");
                        respond(EXCEPTION_RESPONSE, commandNum);
                    }
                }
            } else {
                logger.error("unexpected command type '" + command.getClass().getName() + "'");
                respond(EXCEPTION_RESPONSE, commandNum);
            }
        }
    }

    private void executeAppAndRespond(String appClassName, String threadName, int commandNum)
            throws Exception {

        Class<?> appClass = Class.forName(appClassName);
        String previousThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName(threadName);
        try {
            AppUnderTest app = (AppUnderTest) appClass.newInstance();
            app.executeApp();
            respond("", commandNum);
        } catch (Throwable t) {
            // catch Throwable so response can (hopefully) be sent even under extreme
            // circumstances like OutOfMemoryError
            logger.error(t.getMessage(), t);
            respond(EXCEPTION_RESPONSE, commandNum);
        } finally {
            Thread.currentThread().setName(previousThreadName);
        }
    }

    private void respond(Object response, int commandNum) throws IOException {
        // sychronizing with SocketHeartbeat
        synchronized (objectOut) {
            objectOut.writeObject(new ResponseWrapper(commandNum, response));
        }
    }
}
