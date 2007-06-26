/*-
 * Copyright (C) 2006 Erik Larsson
 *           (C) 2004 vu1tur (not the actual java code, but the C-code which
 *                            has been used for reference)
 * 
 * All rights reserved.
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 
 * 02110-1301, USA
 */

package org.catacombae.dmgx;

import org.catacombae.xml.*;
import org.catacombae.xml.apx.*;
import org.catacombae.io.*;
import net.iharder.Base64;
import java.io.*;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.swing.JOptionPane;
import javax.swing.JFileChooser;
import javax.swing.ProgressMonitor;
import org.xml.sax.XMLReader;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class DMGExtractor {
    public static final String APPNAME = "DMGExtractor 0.51pre";
    public static final String BUILDSTRING = "(Build #" + BuildNumber.BUILD_NUMBER + ")";
    // Constants defining block types in the dmg file
    public static final int BT_ADC = 0x80000004;
    public static final int BT_ZLIB = 0x80000005;
    public static final int BT_BZIP2 = 0x80000006;
    public static final int BT_COPY = 0x00000001;
    public static final int BT_ZERO = 0x00000002;
    public static final int BT_ZERO2 = 0x00000000; // This one also represents a fill of zeroes. What is the difference?
    public static final int BT_END = 0xffffffff;
    public static final int BT_UNKNOWN = 0x7ffffffe;
    public static final long PLIST_ADDRESS_1 = 0x1E0;
    public static final long PLIST_ADDRESS_2 = 0x128;
    public static final String BACKSPACE79 = ""; //"\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b";
//     public static PrintStream stdout = System.out;
//     public static PrintStream stderr = System.err;
    public static BufferedReader stdin = 
	new BufferedReader(new InputStreamReader(System.in));

    public static boolean useSaxParser = false;
    public static boolean verbose = false;
    public static boolean debug = false;
    public static boolean graphical = false;
    public static String startupCommand = "java DMGExtractor";
    public static File dmgFile = null;
    public static File isoFile = null;
    
    /** Used to prevent unneccessary updates of the progress meter. */
    public static long previousPercentage = -1;

    public static ProgressMonitor progmon;

    /* temp */
    private static DummyMonitor dummyMonitor = new DummyMonitor();
    
    public static void main(String[] args) throws Exception {
	try {
	    extractProcedure(args);
	} catch(Exception e) {
	    if(graphical) {
		String stackTrace = e.toString() + "\n";
		for(StackTraceElement ste : e.getStackTrace())
		    stackTrace += "    " + ste.toString() + "\n";
		JOptionPane.showMessageDialog(null, "The program encountered an uncaught exception:\n" + stackTrace + 
					      "\nCan not recover. Exiting...", "Error", JOptionPane.ERROR_MESSAGE);
	    }
	    throw e;
	}
    }
    
    public static void extractProcedure(String[] args) throws Exception {
	if(debug) verbose = true;
	
	parseArgs(args);
	
	printlnVerbose("Processing: \"" + dmgFile + "\"");
	RandomAccessFile dmgRaf = new RandomAccessFile(dmgFile, "r");
	RandomAccessFile isoRaf = null;
	boolean testOnly = false;
	if(isoFile != null) {
	    isoRaf = new RandomAccessFile(isoFile, "rw");
	    isoRaf.setLength(0);
	    printlnVerbose("Extracting to: " + isoFile);
	}
	else {
	    testOnly = true;
	    printlnVerbose("Simulating extraction...");
	}
	
	dmgRaf.seek(dmgRaf.length()-PLIST_ADDRESS_1);
	long plistBegin1 = dmgRaf.readLong();
	long plistEnd = dmgRaf.readLong();
	dmgRaf.seek(dmgRaf.length()-PLIST_ADDRESS_2);
	long plistBegin2 = dmgRaf.readLong();
	long plistSize = dmgRaf.readLong();
	
	if(debug) {
	    println("Read addresses:",
		    "  " + plistBegin1,
		    "  " + plistBegin2);
	}
	if(plistBegin1 != plistBegin2) {
	    println("ERROR: Addresses not equal! Assumption false.",
		    plistBegin1 + " != " + plistBegin2);
	    System.exit(0);
	}
	if(false && plistSize != (plistEnd-plistBegin1)) { // This assumption is proven false. plistEnd means something else
	    println("NOTE: plistSize field does not match plistEnd marker. Assumption false.",
		    "plistSize=" + plistSize + " plistBegin1=" + plistBegin1 + " plistEnd=" + plistEnd + " plistEnd-plistBegin1=" + (plistEnd-plistBegin1));
	}
	printlnVerbose("Jumping to address...");
 	dmgRaf.seek(plistBegin1);
	byte[] buffer = new byte[(int)plistSize];
	dmgRaf.read(buffer);
	
	Plist plist = new Plist(buffer);

// 	String dumpfilename = "dump_" + System.currentTimeMillis() + ".xml";
// 	System.out.println("DUMPING XML DATA TO FILE: \"" + dumpfilename + "\"...");
// 	try { FileOutputStream dump = new FileOutputStream(dumpfilename); dump.write(buffer); dump.close(); }
// 	catch(Exception e) { e.printStackTrace(); }
	

// 	NodeBuilder handler = new NodeBuilder();
// 	if(useSaxParser) {
// 	    InputStream is = new ByteArrayInputStream(buffer);
// 	    SAXParser saxParser = SAXParserFactory.newInstance().newSAXParser();
// 	    try {
// 		if(verbose)
// 		    printSAXParserInfo(saxParser.getXMLReader(), System.out, "");
// 		saxParser.parse(is, handler);
// 	    } catch(SAXException se) {
// 		se.printStackTrace();
// 		System.err.println("Could not read the partition list... exiting.");
// 		System.exit(1);
// 	    }
// 	}
// 	else {
// 	    try {
// 		SynchronizedRandomAccessStream bufferStream =
// 		    new SynchronizedRandomAccessStream(new ByteArrayStream(buffer));
// 		InputStream is = new RandomAccessInputStream(bufferStream);

// 		APXParser encodingParser = APXParser.create(new InputStreamReader(is, "US-ASCII"),
// 							    new NullXMLContentHandler(Charset.forName("US-ASCII")));
// 		String encoding = encodingParser.xmlDecl();
// 		if(verbose) System.out.println("XML Encoding: " + encoding);
// 		if(encoding == null)
// 		    encoding = "US-ASCII";
		
		
// 		is = new RandomAccessInputStream(bufferStream);
// 		Reader usedReader = new BufferedReader(new InputStreamReader(is, encoding));
// 		APXParser documentParser = APXParser.create(usedReader, new NodeBuilderContentHandler(handler, bufferStream,
// 												      Charset.forName(encoding)));
// 		documentParser.xmlDocument();
// 	    } catch(ParseException pe) {
// 		println("Could not read the partition list... exiting.");
// 		System.exit(1);
// 	    }
// 	}
	
// 	XMLNode[] rootNodes = handler.getRoots();
// 	if(rootNodes.length != 1) {
// 	    println("Could not parse UDIF-file!");
// 	    System.exit(0);
// 	}

	/* Ok, now we have a tree built from the XML-document. Let's walk to the right place. */
	/* cd plist 
	   cd dict
	   cdkey resource-fork (type:dict)
	   cdkey blkx (type:array) */
// 	XMLNode current;
// 	XMLElement[] children;
// 	boolean keyFound;
// 	current = rootNodes[0]; //We are at plist... probably (there should be only one root node)
	
// 	current = current.cd("dict");
// 	current = current.cdkey("resource-fork");
// 	current = current.cdkey("blkx");
// 	printlnVerbose("Found " + current.getChildren().length + " partitions:");
	DmgPlistPartition[] partitions = plist.getPartitions();
	
	byte[] inBuffer = new byte[0x40000];
	byte[] outBuffer = new byte[0x40000];

	byte[] zeroblock = new byte[4096];
	/* I think java always zeroes its arrays on creation... 
	   but let's play safe. */
	for(int y = 0; y < zeroblock.length; ++y)
	    zeroblock[y] = 0;

	int partitionNumber = 0;
	int errorsReported = 0;
	int warningsReported = 0;
	long totalSize = 0;
	reportProgress(0);
	for(DmgPlistPartition dpp : partitions) {
	    long partitionSize = dpp.getPartitionSize();
	    totalSize += partitionSize;
	    
	    printlnVerbose("  " + dpp.getName());
	    printlnVerbose("    ID: " + dpp.getID());
	    printlnVerbose("    Attributes: " + dpp.getAttributes());
	    //printlnVerbose("    Partition map data length: " + data.length + " bytes");
	    printlnVerbose("    Partition size: " + partitionSize + " bytes");
	    
	    /* // variable data is not applicable to this reconstructed DMGExtractor
	    if(debug) {
		File dumpFile = new File("data(" + dpp.getID() + ").bin");
		println("    Dumping partition map to file: " + dumpFile);
		    
		FileOutputStream dump = new FileOutputStream(dumpFile);
		dump.write(data);
		dump.close();
	    }
	    */
	    
	    int blockCount = 0;
	    Iterator<DMGBlock> blockIterator = dpp.getBlockIterator();
	    while(blockIterator.hasNext()) {
		if(progmon != null && progmon.isCanceled()) System.exit(0);
		DMGBlock currentBlock = blockIterator.next();
		
		/* Offset of the input data for the current block in the input file */
		final int blockType = currentBlock.getBlockType();
		/* Offset of the input data for the current block in the input file */
		final long inOffset = currentBlock.getTrueInOffset();
		/* Size of the input data for the current block */
		final long inSize = currentBlock.getInSize();
		/* Offset of the output data for the current block in the output file */
		final long outOffset = currentBlock.getTrueOutOffset();
		/* Size of the output data (possibly larger than inSize because of
		   decompression, zero expansion...) */
		final long outSize = currentBlock.getOutSize();
		
		final long trueOutOffset = currentBlock.getTrueOutOffset();
		final long trueInOffset = currentBlock.getTrueInOffset();
		final String blockTypeString = currentBlock.getBlockTypeAsString();
		    
		if(debug) {
		    println("      " + partitionNumber + ":" + blockCount + ". " + blockTypeString + " processing...");
		    println("        outOffset=" + outOffset + " outSize=" + outSize,
			    "        inOffset=" + inOffset + " inSize=" + inSize,
			    "        trueOutOffset=" + trueOutOffset + " trueInOffset=" + trueInOffset);
		}
		
		if(!testOnly && isoRaf.getFilePointer() != trueOutOffset) {
		    println("!------>WARNING: " + blockTypeString + " FP != trueOutOffset (" +
			    isoRaf.getFilePointer() + " != " + trueOutOffset + ")");
		    ++warningsReported;
		}
		    

		if(blockType == BT_ADC) {
		    println("!------>ERROR: BT_ADC not supported.");
		    ++errorsReported;
		    if(!testOnly)
			System.exit(0);
		}
		else if(blockType == BT_ZLIB) {
		    try {
			DMGBlockHandlers.processBlock(currentBlock, dmgRaf, isoRaf, testOnly, dummyMonitor);
		    } catch(DmgException de) {
			de.printStackTrace();
			println("!------>ERROR: BT_ZLIB Could not decode...");
			++errorsReported;
			if(!debug) {
			    println("!------>  outOffset=" + outOffset + " outSize=" + outSize,
				    "          inOffset=" + inOffset + " inSize=" + inSize,
				    "          trueOutOffset=" + trueOutOffset + " trueInOffset=" + trueInOffset);
			}
			if(!testOnly)
			    System.exit(0);
			else {
			    println("!------>  Testing mode, so continuing...");
			    //System.exit(0);
			    break;
			}
		    }
		}
		else if(blockType == BT_BZIP2) {
		    println("!------>ERROR: BT_BZIP2 not currently supported.");
		    ++errorsReported;
		    if(!testOnly)
			System.exit(0);
		}
		else if(blockType == BT_COPY) {
		    DMGBlockHandlers.processBlock(currentBlock, dmgRaf, isoRaf, testOnly, dummyMonitor);
		}
		else if(blockType == BT_ZERO) {
		    DMGBlockHandlers.processBlock(currentBlock, dmgRaf, isoRaf, testOnly, dummyMonitor);
		}
		else if(blockType == BT_ZERO2) {
		    DMGBlockHandlers.processBlock(currentBlock, dmgRaf, isoRaf, testOnly, dummyMonitor);
		}
		else if(blockType == BT_UNKNOWN) {
		    /* I have no idea what this blocktype is... but it's common, and usually
		       doesn't appear more than 2-3 times in a dmg. As long as its input and
		       output sizes are 0, there's no reason to complain... is there? */
		    if(!(inSize == 0 && outSize == 0)) {
			println("!------>ERROR: Blocktype BT_UNKNOWN had non-zero sizes...",
				"!------>  inSize=" + inSize + ", outSize=" + outSize,
				"!------>  Please contact the author of the program to report this bug!");
			
			++errorsReported;
			if(!debug) {
			    println("!------>  outOffset=" + outOffset + " outSize=" + outSize,
				    "          inOffset=" + inOffset + " inSize=" + inSize,
				    "          trueOutOffset=" + trueOutOffset + " trueInOffset=" + trueInOffset);
			}
			if(!testOnly)
			    System.exit(0);
			else {
			    println("!------>  Testing mode, so continuing...");
			    break;
			}
		    }
		}
		else if(blockType == BT_END) {
		    // Nothing needs to be done in this pass.
		}
		else {
		    if(inSize == 0 && outSize == 0) {
			println("!------>WARNING: previously unseen blocktype " + blockType + " [0x" + Integer.toHexString(blockType) + "]",
				"!------>  outOffset=" + outOffset + " outSize=" + outSize + " inOffset=" + inOffset + " inSize=" + inSize,
				"!------>  As inSize and outSize is 0 (block is a marker?), we try to continue the operation...");
			++warningsReported;
		    }
		    else {
			println("!------>ERROR: previously unseen blocktype " + blockType + " [0x" + Integer.toHexString(blockType) + "]",
				"!------>  outOffset=" + outOffset + " outSize=" + outSize + " inOffset=" + inOffset + " inSize=" + inSize,
				"!------>  CRITICAL. inSize and/or outSize are not 0!");
			++errorsReported;
			if(!debug) {
			    println("!------>  outOffset=" + outOffset + " outSize=" + outSize,
				    "          inOffset=" + inOffset + " inSize=" + inSize,
				    "          trueOutOffset=" + trueOutOffset + " trueInOffset=" + trueInOffset);
			}
			if(!testOnly)
			    System.exit(0);
			else {
			    println("!------>  Testing mode, so continuing...");
			    break;
			}
		    }			
		    
		}

		++blockCount;
	    }
	    ++partitionNumber;
	}
	
	
// 	LinkedList<DMGBlock> blocks = new LinkedList<DMGBlock>();
	
// 	long elementNumber = 0;
// 	//long lastOffs = 0;
// 	long lastOutOffset = 0;
// 	long lastInOffset = 0;
// 	long totalSize = 0;
// 	int errorsReported = 0;
// 	int warningsReported = 0;
// 	reportProgress(0);
// 	for(XMLElement xe : current.getChildren()) {
// 	    if(progmon != null && progmon.isCanceled()) System.exit(0);
// 	    if(xe instanceof XMLNode) {
// 		XMLNode xn = (XMLNode)xe;
// 		byte[] data = Base64.decode(Util.readFully(xn.getKeyValue("Data")));
		
// 		long partitionSize = calculatePartitionSize(data);
// 		totalSize += partitionSize;
		
// 		printlnVerbose("  " + xn.getKeyValue("Name"));
// 		printlnVerbose("    ID: " + xn.getKeyValue("ID"));
// 		printlnVerbose("    Attributes: " + xn.getKeyValue("Attributes"));
// 		printlnVerbose("    Partition map data length: " + data.length + " bytes");
// 		printlnVerbose("    Partition size: " + partitionSize + " bytes");
		
// 		if(debug) {
// 		    File dumpFile = new File("data(" + xn.getKeyValue("ID") + ").bin");
// 		    println("    Dumping partition map to file: " + dumpFile);
		    
// 		    FileOutputStream dump = new FileOutputStream(dumpFile);
// 		    dump.write(data);
// 		    dump.close();
// 		}

// 		int offset = 0xCC;
// 		int blockType = 0;
		
// 		/* Offset of the input data for the current block in the input file */
// 		long inOffset = 0;
// 		/* Size of the input data for the current block */
// 		long inSize = 0;
// 		/* Offset of the output data for the current block in the output file */
// 		long outOffset = 0;
// 		/* Size of the output data (possibly larger than inSize because of
// 		   decompression, zero expansion...) */
// 		long outSize = 0;
		
// 		long lastByteReadInBlock = -1;

// 		boolean addInOffset = false;
		
// 		//, lastInOffs = 0;
// 		int blockCount = 0;
// 		while(blockType != BT_END) {		    
// 		    if(progmon != null && progmon.isCanceled()) System.exit(0);
// 		    DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
// 		    int bytesSkipped = 0;
// 		    while(bytesSkipped < offset)
// 			bytesSkipped += dis.skipBytes(offset-bytesSkipped);
		    
// 		    blockType = dis.readInt();
// 		    int skipped = dis.readInt(); //Skip 4 bytes forward
// 		    outOffset = dis.readLong()*0x200;//(dis.readInt() & 0xffffffffL)*0x200; //unsigned int -> long
// 		    //dis.readInt(); //Skip 4 bytes forward
// 		    outSize = dis.readLong()*0x200;//(dis.readInt() & 0xffffffffL)*0x200; //unsigned int -> long
// 		    inOffset = dis.readLong();// & 0xffffffffL; //unsigned int -> long
// 		    //dis.readInt(); //Skip 4 bytes forward
// 		    inSize = dis.readLong();//dis.readInt() & 0xffffffffL; //unsigned int -> long

		    
// 		    String blockTypeString = blockTypeToString(blockType);
// 		    if(debug)
// 			println("      " + elementNumber + ":" + blockCount + ". " + blockTypeString + " processing...");
		    
// 		    if(lastByteReadInBlock == -1)
// 			lastByteReadInBlock = inOffset;
// 		    lastByteReadInBlock += inSize;
		    
// 		    /* The lines below are a "hack" that I had to do to make dmgx work with
// 		       certain dmg-files. I don't understand the issue at all, which is why
// 		       this hack is here, but sometimes inOffset == 0 means that it is 0
// 		       relative to the previous partition's last inOffset. And sometimes it
// 		       doesn't (meaning the actual position 0 in the dmg file). */
// 		    if(addInOffset) {
// 			if(debug)
// 			    println("------->NOTE: addInOffset mode: inOffset tranformation " + inOffset + "->" + (inOffset+lastInOffset));
// 			inOffset += lastInOffset;
// 		    }
// 		    else if(inOffset == 0 && blockCount == 0) {
// 			if(debug)
// 			    println("------->NOTE: Detected inOffset == 0, setting to " + lastInOffset);
// 			addInOffset = true;
// 			inOffset = lastInOffset;
// 		    }
// 		    outOffset += lastOutOffset;
		    
// 		    DMGBlock currentBlock = new DMGBlock(blockType, skipped, outOffset, outSize, inOffset, inSize);
// 		    blocks.add(currentBlock);

// 		    if(debug) {
// 			println("        outOffset=" + outOffset + " outSize=" + outSize + " inOffset=" + inOffset,
// 				"        inSize=" + inSize + " lastOutOffset=" + lastOutOffset + " lastInOffset=" + lastInOffset
// 				/*+ " lastInOffs=" + lastInOffs + " lastOffs=" + lastOffs*/);
// 		    }
		    
// 		    if(blockType == BT_ADC) {
// 			println("!------>ERROR: BT_ADC not supported.");
// 			++errorsReported;
// 			if(!testOnly)
// 			    System.exit(0);
// 		    }
// 		    else if(blockType == BT_ZLIB) {
// // 			if(debug)
// // 			    println("      " + elementNumber + ":"  + blockCount + ". BT_ZLIB processing...");
			
// 			if(!testOnly && isoRaf.getFilePointer() != outOffset)
// 			    println("!------>WARNING: BT_ZLIB FP != outOffset (" +
// 				    isoRaf.getFilePointer() + " != " + outOffset + ")");

// 			try {
// 			    DMGBlockHandlers.processZlibBlock(currentBlock, dmgRaf, isoRaf, testOnly, dummyMonitor);
// 			} catch(DataFormatException dfe) {
// 			    println("!------>ERROR: BT_ZLIB Could not decode...");
// 			    ++errorsReported;
// 			    if(!debug) {
// 				println("!------>  outOffset=" + outOffset + " outSize=" + outSize + 
// 					" inOffset=" + inOffset + " inSize=" + inSize +
// 					" lastOutOffset=" + lastOutOffset + " lastInOffset=" + lastInOffset);
// 			    }
// 			    dfe.printStackTrace();
// 			    if(!testOnly)
// 				System.exit(0);
// 			    else {
// 				println("!------>  Testing mode, so continuing...");
// 				//System.exit(0);
// 				break;
// 			    }
// 			}
// 		    }
// 		    else if(blockType == BT_BZIP2) {
// 			println("!------>ERROR: BT_BZIP2 not currently supported.");
// 			++errorsReported;
// 			if(!testOnly)
// 			    System.exit(0);
// 		    }
// 		    else if(blockType == BT_COPY) {
// // 			if(debug)
// // 			    println("      " + elementNumber + ":" + blockCount + ". BT_COPY processing...");

// 			if(!testOnly && isoRaf.getFilePointer() != outOffset) {
// 			    println("!------>WARNING: BT_COPY FP != outOffset (" + isoRaf.getFilePointer() + " != " + outOffset + ")");
// 			    ++warningsReported;
// 			}
// 			dmgRaf.seek(/*lastOffs+*/inOffset);
			
// 			int bytesRead = dmgRaf.read(inBuffer, 0, Math.min((int)inSize, inBuffer.length));
// 			long totalBytesRead = bytesRead;
// 			while(bytesRead != -1) {
// 			    reportFilePointerProgress(dmgRaf);

// 			    if(!testOnly)
// 				isoRaf.write(inBuffer, 0, bytesRead);
// 			    if(totalBytesRead >= inSize)
// 				break;
// 			    bytesRead = dmgRaf.read(inBuffer, 0, Math.min((int)(inSize-totalBytesRead), inBuffer.length));
// 			    if(bytesRead > 0)
// 				totalBytesRead += bytesRead;
// 			}
			
//  			//lastInOffs = inOffset+inSize;
// 		    }
// 		    else if(blockType == BT_ZERO) {
// // 			if(debug)
// // 			    println("      " + elementNumber + ":" + blockCount + ". BT_ZERO processing...");
// 			if(!testOnly && isoRaf.getFilePointer() != outOffset) {
// 			    println("!------>WARNING: BT_ZERO FP != outOffset (" + 
// 				    isoRaf.getFilePointer() + " != " + outOffset + ")");
// 			    ++warningsReported;
// 			}

// 			reportFilePointerProgress(dmgRaf);

// 			long numberOfZeroBlocks = outSize/zeroblock.length;
// 			int numberOfRemainingBytes = (int)(outSize%zeroblock.length);
// 			for(int j = 0; j < numberOfZeroBlocks; ++j) {
// 			    if(!testOnly)
// 				isoRaf.write(zeroblock);
// 			}
// 			if(!testOnly)
// 			    isoRaf.write(zeroblock, 0, numberOfRemainingBytes);
			
//  			//lastInOffs = inOffset+inSize;
// 		    }
// 		    else if(blockType == BT_ZERO2) {
// // 			if(debug)
// // 			    println("      " + elementNumber + ":" + blockCount + ". BT_ZERO2 processing...");
// 			if(!testOnly && isoRaf.getFilePointer() != outOffset) {
// 			    println("!------>WARNING: BT_ZERO2 FP != outOffset (" + 
// 				    isoRaf.getFilePointer() + " != " + outOffset + ")");
// 			    ++warningsReported;
// 			}

// 			reportFilePointerProgress(dmgRaf);

// 			long numberOfZeroBlocks = outSize/zeroblock.length;
// 			int numberOfRemainingBytes = (int)(outSize%zeroblock.length);
// 			for(int j = 0; j < numberOfZeroBlocks; ++j) {
// 			    if(!testOnly)
// 				isoRaf.write(zeroblock);
// 			}
// 			if(!testOnly)
// 			    isoRaf.write(zeroblock, 0, numberOfRemainingBytes);
			
//  			//lastInOffs = inOffset+inSize;
// 		    }
// 		    else if(blockType == BT_UNKNOWN) {
// 			/* I have no idea what this blocktype is... but it's common, and usually
// 			   doesn't appear more than 2-3 times in a dmg. As long as its input and
// 			   output sizes are 0, there's no reason to complain... is there? */
// // 			if(debug)
// // 			    println("      " + elementNumber + ":" + blockCount + ". BT_UNKNOWN processing...");
// 			if(!(inSize == 0 && outSize == 0)) {
// 			    println("!------>WARNING: Blocktype BT_UNKNOWN had non-zero sizes...",
// 				    "!------>  inSize=" + inSize + ", outSize=" + outSize);
// 			    ++warningsReported;
// 			    //println("        The author of the program would be pleased if you contacted him about this.");
// 			    // ...or would I?
// 			}
// 		    }
// 		    else if(blockType == BT_END) {
// // 			if(debug)
// // 			    println("      " + elementNumber + ":" + blockCount + ". BT_END processing...");
//  			if(!testOnly && isoRaf.getFilePointer() != outOffset) {
// 			    println("!------>WARNING: BT_END FP != outOffset (" + isoRaf.getFilePointer() + " != " + outOffset + ")");
// 			    ++warningsReported;
// 			}
			
// 			//lastOffs += lastInOffs;
// 			lastOutOffset = outOffset;
// 			lastInOffset += lastByteReadInBlock;
// 		    }
//  		    else {
//  			println("!------>WARNING: previously unseen blocktype " + blockType + " [0x" + Integer.toHexString(blockType) + "]",
// 				"!------>  outOffset=" + outOffset + " outSize=" + outSize + " inOffset=" + inOffset + " inSize=" + inSize);
// 			++warningsReported;
			
//  			if(!testOnly && isoRaf.getFilePointer() != outOffset)
// 			    println("!------>WARNING: unknown blocktype FP != outOffset (" +
// 				    isoRaf.getFilePointer() + " != " + outOffset + ")");
			
// 		    }
		    
// 		    offset += 0x28;
// 		    ++blockCount;
// 		}
// 	    }
// 	    ++elementNumber;
// 	}
	//printlnVerbose("Progress: 100% Done!");
	reportProgress(100);
	String summary = (errorsReported != 0)?errorsReported+" errors reported":"No errors reported";
	summary += (warningsReported != 0)?" ("+warningsReported+" warnings emitted).":".";
	if(!graphical) {
	    newline();
	    println(summary);
	    printlnVerbose("Total extracted bytes: " + totalSize + " B");
	}
	else {
	    progmon.close();
	    JOptionPane.showMessageDialog(null, "Extraction complete! " + summary + "\n" +
					  "Total extracted bytes: " + totalSize + " B", 
					  "Information", JOptionPane.INFORMATION_MESSAGE);
	    System.exit(0);
	}

	if(!debug) {
	    if(isoRaf != null)
		isoRaf.close();
	    dmgRaf.close();
	}
	else {
	    if(isoRaf != null)
		isoRaf.close();
// 	System.out.println("blocks.size()=" + blocks.size());
// 	for(DMGBlock b : blocks)
// 	    System.out.println("  " + b.toString());
	    ConcatenatedIterator<DMGBlock> cit = new ConcatenatedIterator<DMGBlock>();
	    for(DmgPlistPartition dpp : partitions)
		cit.add(dpp.getBlockIterator());
	    
	    LinkedList<DMGBlock> merged = mergeBlocks(cit);
// 	System.out.println("merged.size()=" + merged.size());
// 	for(DMGBlock b : merged)
// 	    System.out.println("  " + b.toString());
	    println("Extracting all the parts not containing block data from source file:");
	    int i = 1;
	    DMGBlock previous = null;
	    for(DMGBlock b : merged) {
		if(previous == null && b.getInOffset() > 0) {
		    String filename = i++ + ".block";
		    println("  " + new File(filename).getCanonicalPath() + "...");
		    FileOutputStream curFos = new FileOutputStream(new File(filename));
		    dmgRaf.seek(0);
		    byte[] data = new byte[(int)(b.getInOffset())];
		    dmgRaf.read(data);
		    curFos.write(data);
		    curFos.close();
		}
		else if(previous != null) {
		    String filename = i++ + ".block";
		    println("  " + new File(filename).getCanonicalPath() + "...");
		    FileOutputStream curFos = new FileOutputStream(new File(filename));
		    dmgRaf.seek(previous.getInOffset()+previous.getInSize());
		    byte[] data = new byte[(int)(b.getInOffset()-(previous.getInOffset()+previous.getInSize()))];
		    dmgRaf.read(data);
		    curFos.write(data);
		    curFos.close();
		}
		previous = b;
	    }
	    if(previous.getInOffset()+previous.getInSize() != dmgRaf.length()) {
		String filename = i++ + ".block";
		println("  " + new File(filename).getCanonicalPath() + "...");
		FileOutputStream curFos = new FileOutputStream(new File(filename));
		dmgRaf.seek(previous.getInOffset()+previous.getInSize());
		byte[] data = new byte[(int)(dmgRaf.length()-(previous.getInOffset()+previous.getInSize()))];
		dmgRaf.read(data);
		curFos.write(data);		
		curFos.close();
	    }
	    dmgRaf.close();
	    System.out.println("done!");
	}
    }
    public static void parseArgs(String[] args) {
	boolean parseSuccessful = false;
	try {
	    /* Take care of the options... */
	    int i;
	    for(i = 0; i < args.length; ++i) {
		String cur = args[i];
		//System.out.println("Parsing argument: \"" + cur + "\"");
		if(!cur.startsWith("-"))
		    break;
		else if(cur.equals("-gui")) {
		    graphical = true;

		    // This should be moved to UI class in the future.
		    System.setProperty("swing.aatext", "true"); //Antialiased text
		    try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); }
		    catch(Exception e) {}
		}
		else if(cur.equals("-saxparser"))
		    useSaxParser = true;
		else if(cur.equals("-v"))
		    verbose = true;
		else if(cur.equals("-debug")) {
		    Debug.debug = true;
		    debug = true;
		}
		else if(cur.equals("-startupcommand")) {
		    startupCommand = args[i+1];
		    ++i;
		}
	    }

	    println(APPNAME + " " + BUILDSTRING,
		    "Copyright (c) 2006 Erik Larsson <erik82@kth.se>",
		    "  based on dmg2iso, Copyright (c) 2004 vu1tur <v@vu1tur.eu.org>",
		    "  also using the iHarder Base64 Encoder/Decoder <http://iharder.sf.net>",
		    "",
		    "This program is distributed under the GNU General Public License version 2 or",
		    "later. See <http://www.gnu.org/copyleft/gpl.html> for the details.",
		    "");
	    
	    if(i == args.length) {
		if(graphical) {
		    dmgFile = getInputFileFromUser();
		    if(dmgFile == null)
			System.exit(0);
		    if(getOutputConfirmationFromUser()) {
			isoFile = getOutputFileFromUser();
			if(isoFile == null)
			    System.exit(0);
		    }
		}
		else
		    throw new Exception();
	    }
	    else {
		dmgFile = new File(args[i++]);
		if(!dmgFile.exists()) {
		    println("File \"" + dmgFile + "\" could not be found!");
		    System.exit(0);
		}
		
		if(i >= args.length-1 && !args[i].trim().equals(""))
		    isoFile = new File(args[i++]);
		
		if(i != args.length) {
		    if(!args[i].trim().equals(""))
			throw new Exception();
		}
	    }
	    
	    parseSuccessful = true;
	} catch(Exception e) {
	    e.printStackTrace();
	    printUsageInstructions();
	    System.exit(0);
	}
    }
    private static void printUsageInstructions() {
	println("  usage: " + startupCommand + " [options] <dmgFile> [<isoFile>]",
	        "  if an iso-file is not supplied, the program will simulate an extraction",
	        "  (useful for detecting errors in dmg-files)",
	        "",
	        "  options:",
	        "    -v          verbose operation... for finding out what went wrong",
	        "    -saxparser  use the standard SAX parser for XML processing instead of",
	        "                the homewritten parser (will connect to Apple's website",
	        "                for DTD validation)",
	        "    -debug      performs unspecified debug operations (only intended for",
	        "                development use)",
	        "");
    }
    
    private static String blockTypeToString(int blockType) {
	switch(blockType) {
	case BT_ADC:
	    return "BT_ADC";
	case BT_ZLIB:
	    return "BT_ZLIB";
	case BT_BZIP2:
	    return "BT_BZIP2";
	case BT_COPY:
	    return "BT_COPY";
	case BT_ZERO:
	    return "BT_ZERO";
	case BT_ZERO2:
	    return "BT_ZERO2";
	case BT_END:
	    return "BT_END";
	case BT_UNKNOWN:
	    return "BT_UNKNOWN1";
	default:
	    return "<unknown block type>";
	}
    }
    
    public static long calculatePartitionSize(byte[] data) throws IOException {
	long partitionSize = 0;
	DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
	long totalBytesRead;
	totalBytesRead = 0;
	while(totalBytesRead < 0xCC)
	    totalBytesRead += dis.skip(0xCC);
	
	while(totalBytesRead < data.length) {
	    int bytesRead = 0;
	    while(bytesRead < 0x10)
		bytesRead += dis.skip(0x10-bytesRead);
	    
	    partitionSize += dis.readLong()*0x200;
	    bytesRead += 0x8;
	    
	    while(bytesRead < 0x28)
		bytesRead += dis.skip(0x28-bytesRead);
	    totalBytesRead += bytesRead;
	}
	return partitionSize;
    }

    /** Never used. Java is big-endian. */
    public static int swapEndian(int i) {
	return 
	    ((i & 0xff000000) >> 24) | 
	    ((i & 0x00ff0000) >> 8 ) | 
	    ((i & 0x0000ff00) << 8 ) | 
	    ((i & 0x000000ff) << 24);
    }

    public static void printCurrentLine(String s) {
	//System.out.print(BACKSPACE79);
	System.out.println(s);
    }
    public static void println() {
	System.out.print(BACKSPACE79);
	System.out.println();
    }
    public static void println(String... lines) {
	if(!graphical) {
	    System.out.print(BACKSPACE79);
	    for(String s : lines)
		System.out.println(s);
	}
	else {
	    String resultString = null;
	    for(String s : lines) {
		if(resultString == null)
		    resultString = s;
		else
		    resultString += "\n" + s;
	    }
	    JOptionPane.showMessageDialog(null, resultString, 
					  APPNAME, JOptionPane.INFORMATION_MESSAGE);
	}
    }
    public static void printlnVerbose() {
	if(verbose) {
	    System.out.print(BACKSPACE79);
	    System.out.println();
	}
    }
    public static void printlnVerbose(String... lines) {
	if(verbose) {
	    System.out.print(BACKSPACE79);
	    for(String s : lines)
		System.out.println(s);
	}
    }

    public static void newline() {
	System.out.println();
    }

    public static void printSAXParserInfo(XMLReader saxParser, PrintStream ps, String prefix) throws Exception {
	ps.println(prefix + "Features:");
	ps.println(prefix + "  external-general-entities: " + saxParser.getFeature("http://xml.org/sax/features/external-general-entities"));
	ps.println(prefix + "  external-parameter-entities: " + saxParser.getFeature("http://xml.org/sax/features/external-parameter-entities"));
	ps.println(prefix + "  is-standalone: " + saxParser.getFeature("http://xml.org/sax/features/is-standalone"));
	ps.println(prefix + "  lexical-handler/parameter-entities: " + saxParser.getFeature("http://xml.org/sax/features/lexical-handler/parameter-entities"));
	//ps.println(prefix + "  parameter-entities: " + saxParser.getFeature("http://xml.org/sax/features/parameter-entities"));
	ps.println(prefix + "  namespaces: " + saxParser.getFeature("http://xml.org/sax/features/namespaces"));
	ps.println(prefix + "  namespace-prefixes: " + saxParser.getFeature("http://xml.org/sax/features/namespace-prefixes"));
	ps.println(prefix + "  resolve-dtd-uris: " + saxParser.getFeature("http://xml.org/sax/features/resolve-dtd-uris"));
	ps.println(prefix + "  string-interning: " + saxParser.getFeature("http://xml.org/sax/features/string-interning"));
	ps.println(prefix + "  unicode-normalization-checking: " + saxParser.getFeature("http://xml.org/sax/features/unicode-normalization-checking"));
	ps.println(prefix + "  use-attributes2: " + saxParser.getFeature("http://xml.org/sax/features/use-attributes2"));
	ps.println(prefix + "  use-locator2: " + saxParser.getFeature("http://xml.org/sax/features/use-locator2"));
	ps.println(prefix + "  use-entity-resolver2: " + saxParser.getFeature("http://xml.org/sax/features/use-entity-resolver2"));
	ps.println(prefix + "  validation: " + saxParser.getFeature("http://xml.org/sax/features/validation"));
	ps.println(prefix + "  xmlns-uris: " + saxParser.getFeature("http://xml.org/sax/features/xmlns-uris"));
	ps.println(prefix + "  xml-1.1: " + saxParser.getFeature("http://xml.org/sax/features/xml-1.1"));
	
	ps.println("Properties: ");
	ps.println(prefix + "  declaration-handler: " + saxParser.getProperty("http://xml.org/sax/properties/declaration-handler"));
	ps.println(prefix + "  document-xml-version: " + saxParser.getProperty("http://xml.org/sax/properties/document-xml-version"));
	ps.println(prefix + "  dom-node: " + saxParser.getProperty("http://xml.org/sax/properties/dom-node"));
	ps.println(prefix + "  lexical-handler: " + saxParser.getProperty("http://xml.org/sax/properties/lexical-handler"));
	ps.println(prefix + "  xml-string: " + saxParser.getProperty("http://xml.org/sax/properties/xml-string"));

	    //ps.println("isValidating: " + saxParser.isValidating());
    }
    
    /** Simply calculates the file pointers position relative to the file size as a percentage, and reports it. */
    public static void reportFilePointerProgress(RandomAccessFile raf) throws IOException {
	reportProgress((int)(raf.getFilePointer()*100/raf.length()));
    }

    public static void reportProgress(int progressPercentage) {
	if(progressPercentage != previousPercentage) {
	    previousPercentage = progressPercentage;
	    if(!graphical) {
		printCurrentLine("--->Progress: " + progressPercentage + "%");
	    }
	    else {
		if(progmon == null) {
		    progmon = new ProgressMonitor(null, "Extracting dmg to iso...", "0%", 0, 100);
		    progmon.setProgress(0);
		    progmon.setMillisToPopup(0);
		}
		progmon.setProgress((int)progressPercentage);
		progmon.setNote(progressPercentage + "%");
	    }
	}
    }

    public static File getInputFileFromUser() throws IOException {
	if(!graphical) {
	    //String s = "";
	    while(true) {
		printCurrentLine("Please specify the path to the dmg file to extract from: ");
		File f = new File(stdin.readLine().trim());
		while(!f.exists()) {
		    println("File does not exist!");
		    printCurrentLine("Please specify the path to the dmg file to extract from: ");
		    f = new File(stdin.readLine().trim());
		}
		return f;
	    }
	}
	else {
	    SimpleFileFilter sff = new SimpleFileFilter();
	    sff.addExtension("dmg");
	    sff.setDescription("DMG disk image files");
 	    JFileChooser jfc = new JFileChooser();
	    jfc.setFileFilter(sff);
	    jfc.setMultiSelectionEnabled(false);	    
	    jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
	    jfc.setDialogTitle("Choose the dmg-file to read...");
	    while(true) {
		if(jfc.showDialog(null, "Open") == JFileChooser.APPROVE_OPTION) {
		    File f = jfc.getSelectedFile();
		    if(f.exists())
			return f;
		    else
			JOptionPane.showMessageDialog(null, "The file does not exist! Choose again...", 
						      "Error", JOptionPane.ERROR_MESSAGE);
		}
		else
		    return null;
	    }
	}
    }
    public static boolean getOutputConfirmationFromUser() throws IOException {
	if(!graphical) {
	    String s = "";
	    while(true) {
		printCurrentLine("Do you want to specify an output file (y/n)? ");
		s = stdin.readLine().trim();
		if(s.equalsIgnoreCase("y"))
		    return true;
		else if(s.equalsIgnoreCase("n"))
		    return false;
	    }
	}
	else {
	    return JOptionPane.showConfirmDialog(null, "Do you want to specify an output file?", 
						 "Confirmation", JOptionPane.YES_NO_OPTION, 
						 JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
	}
    }
    public static File getOutputFileFromUser() throws IOException {
	final String msg1 = "Please specify the path of the iso file to extract to: ";
	final String msg2 = "The file already exists. Do you want to overwrite?";
	if(!graphical) {
	    while(true) {
		printCurrentLine(msg1);
		File f = new File(stdin.readLine().trim());
		while(f.exists()) {
		    while(true) {
			printCurrentLine(msg2 + " (y/n)? ");
			String s = stdin.readLine().trim();
			if(s.equalsIgnoreCase("y"))
			    return f;
			else if(s.equalsIgnoreCase("n"))
			    break;
		    }
		    printCurrentLine(msg1);
		    f = new File(stdin.readLine().trim());
		}
		return f;
	    }
	}
	else {
 	    JFileChooser jfc = new JFileChooser();
	    jfc.setMultiSelectionEnabled(false);
	    jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
	    jfc.setDialogTitle("Choose the output iso-file...");
	    while(true) {
		if(jfc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
		    File f = jfc.getSelectedFile();
		    if(!f.exists())
			return f;
		    else if(JOptionPane.showConfirmDialog(null, msg2, "Confirmation", JOptionPane.YES_NO_OPTION, 
							  JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
			return f;
		    }
		}
		else
		    return null;
	    }
	}
    }
    
    public static LinkedList<DMGBlock> mergeBlocks(LinkedList<DMGBlock> blockList) {
	Iterator<DMGBlock> it = blockList.iterator();
	return mergeBlocks(it);
    }
    public static LinkedList<DMGBlock> mergeBlocks(Iterator<DMGBlock> it) {
	LinkedList<DMGBlock> result = new LinkedList<DMGBlock>();
	DMGBlock previous = it.next();
	DMGBlock current;
	while(it.hasNext()) {
	    current = it.next();
	    if(current.getInSize() != 0) {
		if(current.getInOffset() == previous.getInOffset()+previous.getInSize()) {
		    DMGBlock mergedBlock = new DMGBlock(previous.getBlockType(), previous.getSkipped(), previous.getOutOffset(),
							previous.getOutSize()+current.getOutSize(), previous.getInOffset(),
							previous.getInSize()+current.getInSize(),
							previous.getOutOffsetCompensation(), previous.getInOffsetCompensation());
		    previous = mergedBlock;
		}
		else {
		    result.addLast(previous);
		    previous = current;
		}
	    }
	}
	result.addLast(previous);
	return result;
    }
    
    public static class DummyMonitor implements UserInterface {
	public void reportProgress(int progress) {
	    DMGExtractor.reportProgress(progress);
	}
    }
}

