/********************************************************************************
 * Copyright (c) 2015-2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 ********************************************************************************/


package org.eclipse.mdm.mdfsorter;

import java.io.OutputStream;
import java.util.LinkedList;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

/**
 * A Logging Handler, which enables JUnit to check if a specific event occured,
 * by storing all logged messages in an internal list.
 *
 * @author Tobias Leemann
 *
 */
public class MemorizingHandler extends StreamHandler {

	/**
	 * List of all logged messages.
	 */
	LinkedList<LogRecord> memory;

	/**
	 * Messages of higher or equal priority are written to the output.
	 */
	Level outputlevel = Level.INFO;

	public MemorizingHandler(OutputStream out, Formatter f) {
		super(out, f);
		memory = new LinkedList<>();
	}

	/**
	 * Store LogRecord.
	 *
	 * @see java.util.logging.StreamHandler#publish(java.util.logging.LogRecord)
	 */
	@Override
	public void publish(LogRecord record) {
		if (record.getLevel().intValue() >= outputlevel.intValue()) {
			super.publish(record);
			flush();
		}
		memory.add(record);
	}

	public LinkedList<LogRecord> getMemory() {
		return memory;
	}

	/**
	 * This method counts how many time specific message was logged.
	 *
	 * @param msg
	 *            The message to search for.
	 * @return How many times the message was logged. If the message has not
	 *         been logged 0 is returned.
	 */
	public int getOccurences(String msg) {
		int ret = 0;
		for (var rec : memory) {
			if (rec.getMessage().equals(msg)) {
				ret++;
			}
		}
		return ret;
	}

	/**
	 * Deletes all entries in the logging history.
	 */
	public void clearMemory() {
		memory.clear();
	}

	public void setOutputLevel(Level newlvl) {
		outputlevel = newlvl;
	}

}