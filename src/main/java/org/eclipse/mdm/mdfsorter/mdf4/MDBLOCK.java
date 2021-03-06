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


package org.eclipse.mdm.mdfsorter.mdf4;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * <p>
 * THE META DATA BLOCK <code>MDBLOCK</code>
 * </p>
 * The MDBLOCK contains information encoded as XML string. For example this can
 * be comments for the measured data file, file history information or the
 * identification of a channel. This information is ruled by the parent block
 * and follows specific XML schemas definitions.
 *
 * @author Christian Rechner, Tobias Leemann
 */
public class MDBLOCK extends MDF4GenBlock {

	/** Data section */

	// XML string
	// UTF-8 encoded, zero terminated, new line indicated by CR and LF.
	// CHAR
	private String mdData;

	/**
	 * Parse a TXBLOCK from an existing MDFGenBlock
	 *
	 * @param parent
	 *            The already existing MDF Generic Block.
	 */
	public MDBLOCK(MDF4GenBlock parent) {
		super(parent.getPos());
		setLength(parent.getLength());
		setLinkCount(parent.getLinkCount());
		setId(parent.getId());
		setLinks(parent.getLinks());
		parent.setPrec(this);
	}

	public String getMdData() {
		return mdData;
	}

	private void setMdData(String mdData) {
		this.mdData = mdData;
	}

	@Override
	public String toString() {
		return new StringBuilder().append("MDBLOCK [mdData=").append(mdData).append("]").toString();
	}

	/**
	 * Reads a MDBLOCK from its content.
	 *
	 * @param content
	 *            The data section of this block
	 * @throws IOException
	 *             If an I/O error occurs.
	 */
	@Override
	public void parse(byte[] content) throws IOException {
		// ReadXML String
		setMdData(MDF4Util.readCharsUTF8(ByteBuffer.wrap(content), content.length));
		// TODO: Bytes after zero termination?
	}

}
