/*
  * Copyright © 2016 Cask Data, Inc.
  *
  * Licensed under the Apache License, Version 2.0 (the "License"); you may not
  * use this file except in compliance with the License. You may obtain a copy of
  * the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
  * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
  * License for the specific language governing permissions and limitations under
  * the License.
  */

package co.cask.hydrator.plugin.batch.source;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * XMLRecordReader class to read through a given xml document and to output xml blocks as per node path specified.
 */
public class XMLRecordReader extends RecordReader<LongWritable, Map<String, String>> {

  public static final String CLOSING_END_TAG_DELIMITER = ">";
  public static final String OPENING_END_TAG_DELIMITER = "</";
  public static final String CLOSING_START_TAG_DELIMITER = ">";
  public static final String OPENING_START_TAG_DELIMITER = "<";

  private LongWritable currentKey;
  private Map<String, String> currentValue;
  private String nodePath;
  private String fileName;
  private XMLStreamReader reader;
  private String[] nodes;
  private int totalNodes;
  private Map<Integer, String> actualNodeLevelMap;
  private Map<Integer, String> currentNodeLevelMap;
  private int nodeLevel = 0;
  private String tempFilePath = null;
  private Path file;
  private String fileAction;
  private FileSystem fs;
  private String targetFolder;

  public XMLRecordReader(FileSplit split, Configuration conf) throws IOException {
      file = split.getPath();
      fileName = file.toUri().toString();
      fs = file.getFileSystem(conf);
      XMLInputFactory factory = XMLInputFactory.newInstance();
      FSDataInputStream fdDataInputStream = fs.open(file);
      try {
      reader = factory.createXMLStreamReader(fdDataInputStream);
      } catch (XMLStreamException exception) {
        throw new RuntimeException("XMLStreamException exception : ", exception);
      }
      //set required node path details.
      nodePath = conf.get(XMLInputFormat.XML_INPUTFORMAT_NODE_PATH);
      //remove preceding '/' in node path to avoid first unwanted element after split('/')
      if (nodePath.indexOf("/") == 0) {
        nodePath = nodePath.substring(1, nodePath.length());
      }
      nodes = nodePath.split("/");
      totalNodes = nodes.length;

      //map to store node path specified nodes with key as node level
      actualNodeLevelMap = new HashMap<Integer, String>();
      for (int i = 0; i < totalNodes; i++) {
        actualNodeLevelMap.put(i, nodes[i]);
      }
      currentNodeLevelMap = new HashMap<Integer, String>();

      tempFilePath = conf.get(XMLInputFormat.XML_INPUTFORMAT_PROCESSED_DATA_TEMP_FILE);
      fileAction = conf.get(XMLInputFormat.XML_INPUTFORMAT_FILE_ACTION);
      targetFolder = conf.get(XMLInputFormat.XML_INPUTFORMAT_TARGET_FOLDER);
  }

  @Override
  public void close() throws IOException {
    if (reader != null) {
      try {
        reader.close();
      } catch (XMLStreamException exception) {
        // Swallow exception.
      }
    }
  }

  @Override
  public float getProgress() throws IOException {
    return 1.0f;
  }

  @Override
  public LongWritable getCurrentKey() throws IOException, InterruptedException {
    return currentKey;
  }

  @Override
  public Map<String, String> getCurrentValue() throws IOException, InterruptedException {
    return currentValue;
  }

  @Override
  public void initialize(InputSplit split, TaskAttemptContext context) throws IOException, InterruptedException {
  }

  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {
    currentKey = new LongWritable();
    currentValue = new HashMap<String, String>();
    int lastNodeIndex = totalNodes - 1;
    String lastNode = nodes[lastNodeIndex];
    StringBuilder xmlRecord = new StringBuilder();

    //flag to know if xml record matching to node path has been read and ready to use.
    boolean xmlRecordReady = false;
    //flag to know if matching node found as per node path
    boolean nodeFound = false;

    try {
      while (reader.hasNext()) {
        int event = reader.next();

        switch (event) {
          case XMLStreamConstants.START_ELEMENT:
            String nodeNameStart = reader.getLocalName();
            boolean validHierarchy = true;
            currentNodeLevelMap.put(nodeLevel, nodeNameStart);

            //check if node hierarchy matches with expected one.
            for (int j = nodeLevel; j >= 0; j--) {
              if (!currentNodeLevelMap.get(j).equals(actualNodeLevelMap.get(j))) {
                validHierarchy = false;
              }
            }
            //check if node hierarchy is valid and it matches with last node in node path
            //then start appending tag information to create valid XML Record.
            if (validHierarchy && nodeNameStart.equals(lastNode)) {
              appendStartTagInformation(nodeNameStart, xmlRecord);
              //Set flag for valid node path found
              nodeFound = true;
              //set file offset
              currentKey.set(reader.getLocation().getLineNumber());
            } else if (nodeFound) {
              //append all child nodes inside valid node path
              appendStartTagInformation(nodeNameStart, xmlRecord);
            }
            nodeLevel++;
            break;
          case XMLStreamConstants.CHARACTERS:
            if (nodeFound) {
              xmlRecord.append(reader.getText());
            }
            break;
          case XMLStreamConstants.END_ELEMENT:
            String nodeNameEnd = reader.getLocalName();
            if (nodeFound) {
              //add closing tag
              xmlRecord.append(OPENING_END_TAG_DELIMITER).append(nodeNameEnd).append(CLOSING_END_TAG_DELIMITER);
              if (nodeNameEnd.equals(lastNode)) {
                nodeFound = false;
                //set flag for XML record is ready to emit.
                xmlRecordReady = true;
              }
            }
            nodeLevel--;
            break;
          case XMLStreamConstants.START_DOCUMENT:
            break;
        }
        if (xmlRecordReady) {
          currentValue.put(fileName, xmlRecord.toString());
          return true;
        }
      }
    } catch (XMLStreamException exception) {
      throw new IllegalArgumentException(exception.getMessage());
    }
    actionsAfterEOF();
    return false;
  }

  /**
   * Method to append start tag information along with attributes if any
   * @param nodeNameStart
   * @param xmlRecord
   */
  private void appendStartTagInformation(String nodeNameStart, StringBuilder xmlRecord) {
    xmlRecord.append(OPENING_START_TAG_DELIMITER).append(nodeNameStart);
    int count = reader.getAttributeCount();
    for (int i = 0; i < count; i++) {
      xmlRecord.append(" ").append(reader.getAttributeLocalName(i)).append("=\"").append(reader.getAttributeValue(i))
        .append("\"");
    }
    xmlRecord.append(CLOSING_START_TAG_DELIMITER);
  }

  /**
   * Method to take actions after EOF reached.
   * @throws IOException
   */
  private void actionsAfterEOF() throws IOException {
    if (StringUtils.isNotEmpty(fileAction)) {
      processFileAction();
    }
    updateFileTrackingInfo();
  }

  /**
   * Method to process file with actions (Delete, Move, Archive ) specified.
   * @throws IOException
   */
  private void processFileAction() throws IOException {
    fileAction = fileAction.toLowerCase();
    try {
      switch (fileAction) {
        case "delete":
          fs.delete(file, true);
          break;
        case "move":
          Path tagetFileMovePath = new Path(targetFolder + file.getName());
          fs.rename(file, tagetFileMovePath);
          break;
        case "archive":
          FileOutputStream archivedStream = new FileOutputStream(targetFolder + file.getName() + ".zip");
          ZipOutputStream zipArchivedStream = new ZipOutputStream(archivedStream);
          FSDataInputStream fdDataInputStream = fs.open(file);
          zipArchivedStream.putNextEntry(new ZipEntry(file.getName()));
          int length;
          byte[] buffer = new byte[1024];
          while ((length = fdDataInputStream.read(buffer)) > 0) {
            zipArchivedStream.write(buffer, 0, length);
          }
          zipArchivedStream.closeEntry();
          fdDataInputStream.close();
          zipArchivedStream.close();
          fs.delete(file, true);
          break;
        default:
          break;
      }
    } catch (IOException ioe) {
      throw ioe;
    }
  }

  /**
   * Method to update temporary file with latest XML processed information.
   * @throws IOException
   */
  private void updateFileTrackingInfo() throws IOException {
    try {
      File tempFile = new File(tempFilePath);
      FileWriter fw = new FileWriter(tempFile.getAbsoluteFile(), true);
      BufferedWriter bw = new BufferedWriter(fw);
      bw.write(fileName + "\n");
      bw.close();
    } catch (IOException ioe) {
      throw ioe;
    }
  }
}