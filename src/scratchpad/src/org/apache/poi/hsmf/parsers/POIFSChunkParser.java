/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.hsmf.parsers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.ByteChunk;
import org.apache.poi.hsmf.datatypes.ByteStreamChunk;
import org.apache.poi.hsmf.datatypes.Chunk;
import org.apache.poi.hsmf.datatypes.ChunkGroup;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.apache.poi.hsmf.datatypes.DirectoryChunk;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.MessagePropertiesChunk;
import org.apache.poi.hsmf.datatypes.MessageSubmissionChunk;
import org.apache.poi.hsmf.datatypes.NameIdChunks;
import org.apache.poi.hsmf.datatypes.PropertiesChunk;
import org.apache.poi.hsmf.datatypes.RecipientChunks;
import org.apache.poi.hsmf.datatypes.StoragePropertiesChunk;
import org.apache.poi.hsmf.datatypes.StringChunk;
import org.apache.poi.hsmf.datatypes.Types;
import org.apache.poi.hsmf.datatypes.Types.MAPIType;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.DocumentNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.POILogFactory;
import org.apache.poi.util.POILogger;

/**
 * Processes a POIFS of a .msg file into groups of Chunks, such as
 * core data, attachment #1 data, attachment #2 data, recipient
 * data and so on.
 */
public final class POIFSChunkParser {
   private final static POILogger logger = POILogFactory.getLogger(POIFSChunkParser.class);

   public static ChunkGroup[] parse(POIFSFileSystem fs) throws IOException {
      return parse(fs.getRoot());
   }
   public static ChunkGroup[] parse(DirectoryNode node) throws IOException {
      Chunks mainChunks = new Chunks();
      
      ArrayList<ChunkGroup> groups = new ArrayList<>();
      groups.add(mainChunks);

      // Find our top level children
      // Note - we don't handle children of children yet, as
      //  there doesn't seem to be any use of that in Outlook
      for(Entry entry : node) {
         if(entry instanceof DirectoryNode) {
            DirectoryNode dir = (DirectoryNode)entry;
            ChunkGroup group = null;
            
            // Do we know what to do with it?
            if(dir.getName().startsWith(AttachmentChunks.PREFIX)) {
               group = new AttachmentChunks(dir.getName());
            }
            if(dir.getName().startsWith(NameIdChunks.NAME)) {
               group = new NameIdChunks();
            }
            if(dir.getName().startsWith(RecipientChunks.PREFIX)) {
               group = new RecipientChunks(dir.getName());
            }
            
            if(group != null) {
               processChunks(dir, group);
               groups.add(group);
            } else {
               // Unknown directory, skip silently
            }
         }
      }
      
      // Now do the top level chunks
      processChunks(node, mainChunks);
      
      // All chunks are now processed, have the ChunkGroup
      // match up variable-length properties and their chunks
      for (ChunkGroup group : groups) {
         group.chunksComplete();
      }
      
      // Finish
      return groups.toArray(new ChunkGroup[0]);
   }
   
   /**
    * Creates all the chunks for a given Directory, but
    *  doesn't recurse or descend 
    */
   protected static void processChunks(DirectoryNode node, ChunkGroup grouping) {
     TreeMap<Integer, TreeMap<Integer, Chunk>> multivaluedchunks = new TreeMap<>();
     TreeMap<Integer, Integer> multivaluedchunkslengths = new TreeMap<>();
     
      for(Entry entry : node) {
         if(entry instanceof DocumentNode) {
            process(entry, grouping, multivaluedchunks, multivaluedchunkslengths);
         } else if(entry instanceof DirectoryNode) {
             if(entry.getName().endsWith(Types.DIRECTORY.asFileEnding())) {
                 process(entry, grouping, multivaluedchunks, multivaluedchunkslengths);
             }
         }
      }
      //
      // Finish up variable length multivalued properties
      //
      for (Map.Entry<Integer, Integer> multivaluedprop : multivaluedchunkslengths.entrySet()) {
        TreeMap<Integer, Chunk> propvals = multivaluedchunks.get(multivaluedprop.getKey());
        for (int i = 0;i < multivaluedprop.getValue(); i++) {
          Chunk propval = propvals != null ? propvals.get(i) : null;
          if (propval != null) {
            grouping.record(propval);
          }
        }
      }
   }
   
   /**
    * Creates a chunk, and gives it to its parent group 
    */
   protected static void process(Entry entry, ChunkGroup grouping,
      TreeMap<Integer/*chunk id*/, TreeMap<Integer/*multivalue index*/, Chunk>> multivaluedchunks,
      TreeMap<Integer/*chunk id*/, Integer/*multivalue length*/> multivaluedchunkslengths) {
      String entryName = entry.getName();
      Chunk chunk = null;
      
      // Is it a properties chunk? (They have special names)
      if (entryName.equals(PropertiesChunk.NAME)) {
         if (grouping instanceof Chunks) {
            // These should be the properties for the message itself
            chunk = new MessagePropertiesChunk(grouping,
              entry.getParent() != null && entry.getParent().getParent() != null);
         } else {
            // Will be properties on an attachment or recipient
            chunk = new StoragePropertiesChunk(grouping);
         }
      } else {
         // Check it's a regular chunk
         if(entryName.length() < 9) {
            // Name in the wrong format
            return;
         }
         if(! entryName.contains("_")) {
            // Name in the wrong format
            return;
         }
         
         // Split it into its parts
         int splitAt = entryName.lastIndexOf('_');
         String namePrefix = entryName.substring(0, splitAt+1);
         String ids = entryName.substring(splitAt+1);
         long multivalueidx = -1;
         boolean ismvvaluestream = false;
         splitAt = ids.lastIndexOf('-');
         if (splitAt >= 0) {
            ismvvaluestream = true;
            String mvidxstr = ids.substring(splitAt + 1);
            try {
               multivalueidx = Long.parseLong(mvidxstr);
               multivalueidx = multivalueidx & 0xFFFFFFFF;
            } catch (NumberFormatException e) {
               // Name in the wrong format
               return;
            }
         }
         
         // Make sure we got what we expected, should be of 
         //  the form __<name>_<id><type>
         if(namePrefix.equals("Olk10SideProps") ||
            namePrefix.equals("Olk10SideProps_")) {
            // This is some odd Outlook 2002 thing, skip
            return;
         } else if(splitAt <= entryName.length()-8) {
            // In the right form for a normal chunk
            // We'll process this further in a little bit
         } else {
            // Underscores not the right place, something's wrong
            throw new IllegalArgumentException("Invalid chunk name " + entryName);
         }
         
         // Now try to turn it into id + type
         try {
            int chunkId = Integer.parseInt(ids.substring(0, 4), 16);
            int typeId  = Integer.parseInt(ids.substring(4, 8), 16);
            
            boolean multivalued = false;
            if ((typeId & Types.MULTIVALUED_FLAG) != 0) {
               typeId -= Types.MULTIVALUED_FLAG;
               multivalued = true;
            }
            MAPIType type = Types.getById(typeId);
            if (type == null) {
               type = Types.createCustom(typeId);
            }
            
            // Special cases based on the ID
            if (chunkId == MAPIProperty.MESSAGE_SUBMISSION_ID.id) {
               chunk = new MessageSubmissionChunk(namePrefix, chunkId, type);
            }
            else if (type == Types.BINARY && chunkId == MAPIProperty.ATTACH_DATA.id) {
               chunk = new ByteStreamChunk(namePrefix, chunkId, type);
            }
            else {
               // Nothing special about this ID
               // So, do the usual thing which is by type
               if (multivalued) {
                  if (!ismvvaluestream) {
                     ByteChunk mvlengthchunk = new ByteChunk(chunkId, Types.BINARY);
                     if(entry instanceof DocumentNode) {
                        DocumentInputStream inp = null;
                        try {
                           inp = new DocumentInputStream((DocumentNode) entry);
                           mvlengthchunk.readValue(inp);
                           byte[] mvlength = mvlengthchunk.getValue();
                           int len = mvlength.length / 4;
                           multivaluedchunkslengths.put(chunkId, len);
                        } catch(IOException e) {
                           logger.log(POILogger.ERROR, "Error reading from part " + entry.getName() + " - " + e);
                        }
                     }
                  }
                  else if (multivalueidx >= 0) {
                     TreeMap<Integer, Chunk> chunklist = multivaluedchunks.get(chunkId);
                     if (chunklist == null) {
                        chunklist = new TreeMap<>();
                        multivaluedchunks.put(chunkId, chunklist);
                     }
                     Chunk valchunk = null;
                     if (type == Types.BINARY) {
                        valchunk = new ByteChunk(namePrefix, chunkId, type);
                     } else if (type == Types.ASCII_STRING ||
                                type == Types.UNICODE_STRING) {
                        valchunk = new StringChunk(namePrefix, chunkId, type);
                     } else {
                        System.out.println("UNSUPPORTED MULTIVALUED PROP TYPE " + entryName);
                        // Type of an unsupported multivalued type! Skipping...
                     }
                     if (valchunk != null) {
                        DocumentInputStream inp = null;
                        try {
                           inp = new DocumentInputStream((DocumentNode) entry);
                           valchunk.readValue(inp);
                        } catch (IOException e) {
                           logger.log(POILogger.ERROR, "Error reading from part " + entry.getName() + " - " + e);
                        }
                        chunklist.put((int) multivalueidx, valchunk);
                     }
                  }
               } else {
                  if (type == Types.DIRECTORY) {
                     if (entry instanceof DirectoryNode) {
                        chunk = new DirectoryChunk((DirectoryNode) entry, namePrefix, chunkId, type);
                     }
                  } else if (type == Types.BINARY) {
                     chunk = new ByteChunk(namePrefix, chunkId, type);
                  } else if (type == Types.ASCII_STRING ||
                             type == Types.UNICODE_STRING) {
                     chunk = new StringChunk(namePrefix, chunkId, type);
                  } else {
                     System.out.println("UNSUPPORTED PROP TYPE " + entryName);
                     // Type of an unsupported type! Skipping...
                  }
               }
            }
         } catch (NumberFormatException e) {
           // Name in the wrong format
           return;
         }
      }
      
      if(chunk != null) {
          if(entry instanceof DocumentNode) {
             DocumentInputStream inp = null;
             try {
                inp = new DocumentInputStream((DocumentNode)entry);
                chunk.readValue(inp);
                grouping.record(chunk);
             } catch(IOException e) {
                logger.log(POILogger.ERROR, "Error reading from part " + entry.getName() + " - " + e);
             } finally {
                // Do not close stream referenced by ByteStreamChunk
                if (inp != null && !(chunk instanceof ByteStreamChunk)) inp.close();
             }
          } else {
             grouping.record(chunk);
          }
      }
   }
}
