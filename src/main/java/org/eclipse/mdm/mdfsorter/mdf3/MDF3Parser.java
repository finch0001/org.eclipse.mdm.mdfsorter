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


package org.eclipse.mdm.mdfsorter.mdf3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.logging.Level;

import org.eclipse.mdm.mdfsorter.MDFAbstractParser;
import org.eclipse.mdm.mdfsorter.MDFFileContent;
import org.eclipse.mdm.mdfsorter.MDFParser;
import org.eclipse.mdm.mdfsorter.MDFSorter;
import org.eclipse.mdm.mdfsorter.mdf4.MDF4Util;

public class MDF3Parser extends MDFAbstractParser<MDF3GenBlock> {

	public MDF3Parser(FileChannel in, boolean isBigEndian) {
		super(in);
		this.isBigEndian = isBigEndian;
	}

	public boolean isBigEndian = false;

	/**
	 * Helper Method to read to an array from a FileChannel.
	 *
	 * @param bytes
	 *            The number of bytes to read.
	 * @param in
	 *            The FileChannel to read from.
	 * @return A byte-Array with <code>length=bytes</code> filled with the next
	 *         bytes from the Channel.
	 * @throws IOException
	 *             If an input error occurs.
	 */
	private static byte[] readBytes(int bytes, FileChannel in) throws IOException {
		ByteBuffer chunk = ByteBuffer.allocate(bytes);
		int bytesread = 0;
		if ((bytesread = in.read(chunk)) != bytes) {
			System.err.println(new StringBuilder().append("Read only ").append(bytesread).append(" Bytes instead of ").append(bytes).toString());
		}
		return chunk.array();
	}

	/**
	 * Parses the file and returns the root of the tree structure.
	 *
	 * @return The HeaderBlock that is root of the file
	 * @throws IOException
	 */
	private MDF3GenBlock parseBlocks() throws IOException {
		// Add headerblock to the queue
		MDF3GenBlock g = new MDF3GenBlock(64, isBigEndian);
		queue.add(g);
		var ret = g;
		do {
			skipped.clear();
			while (!queue.isEmpty()) {
				MDF3GenBlock next = queue.poll();

				if (blocklist.containsKey(next.getPos())) {
					throw new RuntimeException("Duplicate Block in list.");
				}

				if (next.getPos() < lasthandled) {
					skipped.add(next);
					continue;
				}

				// parse.
				getBlockHeader(next);
				forceparse(next);

				// Add (if possible the more precise) block to the blocklist
				unfinished.remove(next.getPos());
				if (next.getPrec() != null) {
					blocklist.put(next.getPos(), next.getPrec());
				} else {
					blocklist.put(next.getPos(), next);
				}

				lasthandled = next.getPos();

				foundblocks++;
			}
			in.position(0L);
			queue.addAll(skipped);
			lasthandled = 0;
			fileruns++;
		} while (!skipped.isEmpty()); // another run is needed

		MDFSorter.log.log(Level.INFO, new StringBuilder().append("Needed ").append(fileruns).append(" runs.").toString());
		MDFSorter.log.log(Level.INFO, new StringBuilder().append("Found ").append(blocklist.size()).append(" blocks.").toString());
		MDFSorter.log.log(Level.FINE, "ValidatorListSize: " + (foundblocks + 1)); // Expected
																					// number
		// of node in Vector
		// MDFValidators
		// node list for
		// this file
		return ret;
	}

	/**
	 * Reads the Block Header of an MDFGenBlock, where only the position value
	 * is set. The header includes ID, linkcount and length and also the links
	 * of the link section!
	 *
	 * @throws IOException
	 *             If a reading error occurs.
	 */
	private void getBlockHeader(MDF3GenBlock start) throws IOException {
		in.position(start.getPos());
		byte[] head = readBytes(4, in);
		// Read header of this block
		String blktyp = MDF3Util.readCharsISO8859(MDFParser.getDataBuffer(head, 0, 2), 2);
		start.setId(blktyp);
		int blklength = MDF4Util.readUInt16(MDFParser.getDataBuffer(head, 2, 4));
		start.setLength(blklength);

		// set standard link-count
		int blklinkcount = MDF3Util.getLinkcount(blktyp);
		start.setLinkCount(blklinkcount);

		// Read links and create new blocks
		head = readBytes(blklinkcount * 4, in);
		long position = in.position();
		for (int i = 0; i < blklinkcount; i++) {
			long nextlink = MDF3Util.readLink(MDFParser.getDataBuffer(head, i * 4, (i + 1) * 4), isBigEndian);
			if (nextlink != 0) {
				if ("DG".equals(blktyp) && i == 3) {
					// special case: pointer to data section (4th link of a DG-BLock)

					/*
					 * the following lines ensure validity of a given link to a DTBLOCK,
					 * since there are cases where a LINK is given but points to a non
					 * DTBLOCK. therefore such links are only proceeded if the corresponding
					 * DGBLOCK has at least one CGBLOCK with at least one measured value!
					 */
					var dgBlock = new DGBLOCK(start);
					in.position(dgBlock.getPos() + 20);
					byte[] bb = readBytes(2, in);
					int cgCount = MDF3Util.readUInt16(MDFParser.getDataBuffer(bb, 0, 2), isBigEndian);
					if (cgCount < 1) {
						// ignore link, since DGBLOCK does not have any CGBLOCKs
						continue;
					}

					// iterate over all CGBLOCKS. link is valid if at least one of them has measured values
					var cgBlock = new CGBLOCK(dgBlock.getLnkCgFirst());
					while (cgBlock != null) {
						in.position(cgBlock.getPos() + 22);
						bb = readBytes(4, in);
						long cycleCount = MDF3Util.readUInt32(MDFParser.getDataBuffer(bb, 0, 4), isBigEndian);
						if (cycleCount > 0) {
							checkFoundDataBlockLink(start, nextlink, i);
							break;
						}
						cgBlock = --cgCount == 0 ? null : new CGBLOCK(cgBlock.getLnkCgNext());
					}
				} else {
					checkFoundLink(start, nextlink, i);
				}
			}
		}

		// set to begin of data section.
		in.position(position);

		// read possible extra links in CGBLOCK
		if ("CG".equals(blktyp) && blklength == 30) {
			head = readBytes(14, in);
			long nextlink = MDF3Util.readLink(MDFParser.getDataBuffer(head, 10, 14), isBigEndian);
			start.moreLinks(4);
			checkFoundLink(start, nextlink, 3);
		}

		// read possible extra links CNBLOCK
		if ("CN".equals(blktyp) && blklength > 218) {
			head = readBytes(198, in);
			long nextlink = MDF3Util.readLink(MDFParser.getDataBuffer(head, 194, 198), isBigEndian);
			start.moreLinks(6);
			if (nextlink != 0) {
				checkFoundLink(start, nextlink, 5);
			}

			if (blklength > 222) {
				head = readBytes(4, in);
				nextlink = MDF3Util.readLink(MDFParser.getDataBuffer(head, 0, 4), isBigEndian);
				start.moreLinks(7);
				if (nextlink != 0) {
					checkFoundLink(start, nextlink, 6);
				}
			}
		}

		// read possible extra links CCBLOCK
		if ("CC".equals(blktyp)) {
			head = readBytes(40, in);
			int convtype = MDF3Util.readUInt16(MDFParser.getDataBuffer(head, 38, 40), isBigEndian);
			if (convtype == 12) {
				// TextTable has links to textblocks, get number
				head = readBytes(2, in);
				int numberOfValues = MDF3Util.readUInt16(MDFParser.getDataBuffer(head, 0, 2), isBigEndian);
				start.moreLinks(numberOfValues);
				head = readBytes((8 + 8 + 4) * numberOfValues, in);
				for (int i = 0; i < numberOfValues; i++) {
					long nextlink = MDF3Util.readLink(MDFParser.getDataBuffer(head, i * 20 + 16, (i + 1) * 20),
							isBigEndian);
					if (nextlink != 0) {
						checkFoundLink(start, nextlink, i);
					}
				}
			}
		}

		// read possible extra links CDBLOCK
		if ("CD".equals(blktyp)) {
			head = readBytes(4, in);
			int numdep = MDF3Util.readUInt16(MDFParser.getDataBuffer(head, 2, 4), isBigEndian);
			start.moreLinks(2 * numdep);
			head = readBytes(8 * numdep, in);
			for (int i = 0; i < 2 * numdep; i++) {
				long nextlink = MDF3Util.readLink(MDFParser.getDataBuffer(head, 4 * i, 4 * (i + 1)), isBigEndian);
				if (nextlink != 0) {
					checkFoundLink(start, nextlink, i);
				}
			}
		}

		// set stream back to start of data section.
		in.position(position);
	}

	/**
	 * Checks if a newly found link, already exists in some other list. If not,
	 * it is added to the blocklist.
	 *
	 * @param start
	 *            The block, as which child this link was recovered.
	 * @param address
	 *            The offset in the file of this link.
	 * @param chldnum
	 *            The number of this link.
	 */
	public void checkFoundLink(MDF3GenBlock start, long address, int chldnum) {
		if (blocklist.containsKey(address)) {
			start.addLink(chldnum, blocklist.get(address));
			foundblocks++;
		} else if (unfinished.containsKey(address)) {
			start.addLink(chldnum, unfinished.get(address));
			foundblocks++;
		} else {
			MDF3GenBlock child = new MDF3GenBlock(address, isBigEndian);
			start.addLink(chldnum, child);
			queue.add(child);
			unfinished.put(address, child);
		}
	}

	/**
	 * If a newly found block links to a data section, it is immediately put
	 * into the blocklist.
	 *
	 * @param start
	 *            The parent block of the found data section
	 * @param address
	 *            The address of the found data block.
	 * @param chldnum
	 *            The number of the link from startblock to the found
	 *            childblock.
	 */
	public void checkFoundDataBlockLink(MDF3GenBlock start, long address, int chldnum) {
		foundblocks++;
		if (blocklist.containsKey(address)) {
			start.addLink(chldnum, blocklist.get(address));
		} else {
			MDF3GenBlock child = new MDF3GenBlock(address, isBigEndian);
			child.setId("DT"); // TODO Other types;
			child.setLinkCount(0);
			child = new DTBLOCK(child);
			blocklist.put(address, child);
			start.addLink(chldnum, child);
		}
	}

	/**
	 * This method creates the corresponding specialized block and calls the
	 * parse method on Block blk
	 *
	 * @param blk
	 * @throws IOException
	 */
	/**
	 * @param blk
	 * @throws IOException
	 */
	private void forceparse(MDF3GenBlock blk) throws IOException {

		long sectionsize = blk.getLength() - 4 - 4 * MDF3Util.getLinkcount(blk.getId());

		byte[] content = null;

		// parse special blocktypes more precisely.
		content = readBytes((int) sectionsize, in);

		MDF3GenBlock sp = null;

		switch (blk.getId()) {
		case "CG":
			sp = new CGBLOCK(blk);
			break;
		case "DG":
			sp = new DGBLOCK(blk);
			break;
		case "CN":
			sp = new CNBLOCK(blk);
			break;
		case "HD":
			sp = new HDBLOCK(blk);
			break;
		case "CC":
			sp = new CCBLOCK(blk);
			break;
		case "CD":
			sp = new CDBLOCK(blk);
			break;
		}

		if (sp != null) {
			sp.parse(content);
		}
	}

	private LinkedList<MDF3GenBlock> getBlocklist() {
		LinkedList<MDF3GenBlock> writelist = new LinkedList<>();
		while (!blocklist.isEmpty()) {
			writelist.addFirst(blocklist.pollLastEntry().getValue());

		}
		return writelist;
	}

	@Override
	public MDFFileContent<MDF3GenBlock> parse() throws IOException {

		var tree = parseBlocks();

		// 2. run through tree, and change all blocks to their specials if they
		// exist

		var structlist = getBlocklist();
		if (tree.getPrec() != null) {
			tree = tree.getPrec();
		}
		// update children and calculate length of data blocks
		structlist.forEach(MDF3GenBlock::updateChildren);

		// calculate length of data sections.
		for (var blk : structlist) {
			if (blk instanceof DGBLOCK) {
				var dgthis = (DGBLOCK) blk;
				var datasec = dgthis.getLnkData();
				if (datasec != null) {
					long datalength = 0;
					var cgfirst = dgthis.getLnkCgFirst();
					if (!(cgfirst instanceof CGBLOCK)) {
						throw new RuntimeException("Error reading CGBLOCK");
					}
					var childcg = (CGBLOCK) dgthis.getLnkCgFirst();
					do {
						datalength += (childcg.getDataBytes() + dgthis.getNumOfRecId()) * childcg.getCycleCount();
					} while ((childcg = (CGBLOCK) childcg.getLnkCgNext()) != null);

					datasec.setLength(datalength);
				}
			}
		}
		tree.updateChildren();

		return new MDFFileContent<>(in, tree, structlist, true);
	}

}
