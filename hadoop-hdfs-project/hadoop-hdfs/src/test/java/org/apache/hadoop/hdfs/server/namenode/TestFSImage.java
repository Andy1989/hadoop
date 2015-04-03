/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.EnumSet;

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoContiguous;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoStriped;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.StartupOption;
import org.junit.Assert;

import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSOutputStream;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream.SyncFlag;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.namenode.LeaseManager.Lease;
import org.apache.hadoop.hdfs.util.MD5FileUtils;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.PathUtils;
import org.junit.Test;

public class TestFSImage {

  private static final String HADOOP_2_7_ZER0_BLOCK_SIZE_TGZ =
      "image-with-zero-block-size.tar.gz";
  @Test
  public void testPersist() throws IOException {
    Configuration conf = new Configuration();
    testPersistHelper(conf);
  }

  @Test
  public void testCompression() throws IOException {
    Configuration conf = new Configuration();
    conf.setBoolean(DFSConfigKeys.DFS_IMAGE_COMPRESS_KEY, true);
    conf.set(DFSConfigKeys.DFS_IMAGE_COMPRESSION_CODEC_KEY,
        "org.apache.hadoop.io.compress.GzipCodec");
    testPersistHelper(conf);
  }

  private void testPersistHelper(Configuration conf) throws IOException {
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).build();
      cluster.waitActive();
      FSNamesystem fsn = cluster.getNamesystem();
      DistributedFileSystem fs = cluster.getFileSystem();

      final Path dir = new Path("/abc/def");
      final Path file1 = new Path(dir, "f1");
      final Path file2 = new Path(dir, "f2");

      // create an empty file f1
      fs.create(file1).close();

      // create an under-construction file f2
      FSDataOutputStream out = fs.create(file2);
      out.writeBytes("hello");
      ((DFSOutputStream) out.getWrappedStream()).hsync(EnumSet
          .of(SyncFlag.UPDATE_LENGTH));

      // checkpoint
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

      cluster.restartNameNode();
      cluster.waitActive();
      fs = cluster.getFileSystem();

      assertTrue(fs.isDirectory(dir));
      assertTrue(fs.exists(file1));
      assertTrue(fs.exists(file2));

      // check internals of file2
      INodeFile file2Node = fsn.dir.getINode4Write(file2.toString()).asFile();
      assertEquals("hello".length(), file2Node.computeFileSize());
      assertTrue(file2Node.isUnderConstruction());
      BlockInfo[] blks = file2Node.getBlocks();
      assertEquals(1, blks.length);
      assertEquals(BlockUCState.UNDER_CONSTRUCTION, blks[0].getBlockUCState());
      // check lease manager
      Lease lease = fsn.leaseManager.getLeaseByPath(file2.toString());
      Assert.assertNotNull(lease);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  private void testSaveAndLoadStripedINodeFile(FSNamesystem fsn, Configuration conf,
                                               boolean isUC) throws IOException{
    // contruct a INode with StripedBlock for saving and loading
    fsn.createErasureCodingZone("/", false);
    long id = 123456789;
    byte[] name = "testSaveAndLoadInodeFile_testfile".getBytes();
    PermissionStatus permissionStatus = new PermissionStatus("testuser_a",
            "testuser_groups", new FsPermission((short)0x755));
    long mtime = 1426222916-3600;
    long atime = 1426222916;
    BlockInfoContiguous[] blks = new BlockInfoContiguous[0];
    short replication = 3;
    long preferredBlockSize = 128*1024*1024;
    INodeFile file = new INodeFile(id, name, permissionStatus, mtime, atime,
        blks, replication, preferredBlockSize);
    ByteArrayOutputStream bs = new ByteArrayOutputStream();
    file.addStripedBlocksFeature();

    //construct StripedBlocks for the INode
    BlockInfoStriped[] stripedBlks = new BlockInfoStriped[3];
    long stripedBlkId = 10000001;
    long timestamp = mtime+3600;
    for (int i = 0; i < stripedBlks.length; i++) {
      stripedBlks[i] = new BlockInfoStriped(
              new Block(stripedBlkId + i, preferredBlockSize, timestamp),
              (short) 6, (short) 3);
      file.getStripedBlocksFeature().addBlock(stripedBlks[i]);
    }

    final String client = "testClient";
    final String clientMachine = "testClientMachine";
    final String path = "testUnderConstructionPath";

    //save the INode to byte array
    DataOutput out = new DataOutputStream(bs);
    if (isUC) {
      file.toUnderConstruction(client, clientMachine);
      FSImageSerialization.writeINodeUnderConstruction((DataOutputStream) out,
          file, path);
    } else {
      FSImageSerialization.writeINodeFile(file, out, false);
    }
    DataInput in = new DataInputStream(
            new ByteArrayInputStream(bs.toByteArray()));

    // load the INode from the byte array
    INodeFile fileByLoaded;
    if (isUC) {
      fileByLoaded = FSImageSerialization.readINodeUnderConstruction(in,
              fsn, fsn.getFSImage().getLayoutVersion());
    } else {
      fileByLoaded = (INodeFile) new FSImageFormat.Loader(conf, fsn)
              .loadINodeWithLocalName(false, in, false);
    }

    assertEquals(id, fileByLoaded.getId() );
    assertArrayEquals(isUC ? path.getBytes() : name,
        fileByLoaded.getLocalName().getBytes());
    assertEquals(permissionStatus.getUserName(),
        fileByLoaded.getPermissionStatus().getUserName());
    assertEquals(permissionStatus.getGroupName(),
        fileByLoaded.getPermissionStatus().getGroupName());
    assertEquals(permissionStatus.getPermission(),
        fileByLoaded.getPermissionStatus().getPermission());
    assertEquals(mtime, fileByLoaded.getModificationTime());
    assertEquals(isUC ? mtime : atime, fileByLoaded.getAccessTime());
    assertEquals(0, fileByLoaded.getContiguousBlocks().length);
    assertEquals(0, fileByLoaded.getBlockReplication());
    assertEquals(preferredBlockSize, fileByLoaded.getPreferredBlockSize());

    //check the BlockInfoStriped
    BlockInfoStriped[] stripedBlksByLoaded =
        fileByLoaded.getStripedBlocksFeature().getBlocks();
    assertEquals(3, stripedBlksByLoaded.length);
    for (int i = 0; i < 3; i++) {
      assertEquals(stripedBlks[i].getBlockId(),
          stripedBlksByLoaded[i].getBlockId());
      assertEquals(stripedBlks[i].getNumBytes(),
          stripedBlksByLoaded[i].getNumBytes());
      assertEquals(stripedBlks[i].getGenerationStamp(),
          stripedBlksByLoaded[i].getGenerationStamp());
      assertEquals(stripedBlks[i].getDataBlockNum(),
          stripedBlksByLoaded[i].getDataBlockNum());
      assertEquals(stripedBlks[i].getParityBlockNum(),
          stripedBlksByLoaded[i].getParityBlockNum());
    }

    if (isUC) {
      assertEquals(client,
          fileByLoaded.getFileUnderConstructionFeature().getClientName());
      assertEquals(clientMachine,
          fileByLoaded.getFileUnderConstructionFeature().getClientMachine());
    }
  }

  /**
   * Test if a INodeFile with BlockInfoStriped can be saved by
   * FSImageSerialization and loaded by FSImageFormat#Loader.
   */
  @Test
  public void testSaveAndLoadStripedINodeFile() throws IOException{
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).build();
      cluster.waitActive();
      testSaveAndLoadStripedINodeFile(cluster.getNamesystem(), conf, false);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Test if a INodeFileUnderConstruction with BlockInfoStriped can be
   * saved and loaded by FSImageSerialization
   */
  @Test
  public void testSaveAndLoadStripedINodeFileUC() throws IOException{
    // construct a INode with StripedBlock for saving and loading
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).build();
      cluster.waitActive();
      testSaveAndLoadStripedINodeFile(cluster.getNamesystem(), conf, true);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Ensure that the digest written by the saver equals to the digest of the
   * file.
   */
  @Test
  public void testDigest() throws IOException {
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      DistributedFileSystem fs = cluster.getFileSystem();
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);
      File currentDir = FSImageTestUtil.getNameNodeCurrentDirs(cluster, 0).get(
          0);
      File fsimage = FSImageTestUtil.findNewestImageFile(currentDir
          .getAbsolutePath());
      assertEquals(MD5FileUtils.readStoredMd5ForFile(fsimage),
          MD5FileUtils.computeMd5ForFile(fsimage));
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Ensure mtime and atime can be loaded from fsimage.
   */
  @Test(timeout=60000)
  public void testLoadMtimeAtime() throws Exception {
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
      cluster.waitActive();
      DistributedFileSystem hdfs = cluster.getFileSystem();
      String userDir = hdfs.getHomeDirectory().toUri().getPath().toString();
      Path file = new Path(userDir, "file");
      Path dir = new Path(userDir, "/dir");
      Path link = new Path(userDir, "/link");
      hdfs.createNewFile(file);
      hdfs.mkdirs(dir);
      hdfs.createSymlink(file, link, false);

      long mtimeFile = hdfs.getFileStatus(file).getModificationTime();
      long atimeFile = hdfs.getFileStatus(file).getAccessTime();
      long mtimeDir = hdfs.getFileStatus(dir).getModificationTime();
      long mtimeLink = hdfs.getFileLinkStatus(link).getModificationTime();
      long atimeLink = hdfs.getFileLinkStatus(link).getAccessTime();

      // save namespace and restart cluster
      hdfs.setSafeMode(HdfsConstants.SafeModeAction.SAFEMODE_ENTER);
      hdfs.saveNamespace();
      hdfs.setSafeMode(HdfsConstants.SafeModeAction.SAFEMODE_LEAVE);
      cluster.shutdown();
      cluster = new MiniDFSCluster.Builder(conf).format(false)
          .numDataNodes(1).build();
      cluster.waitActive();
      hdfs = cluster.getFileSystem();
      
      assertEquals(mtimeFile, hdfs.getFileStatus(file).getModificationTime());
      assertEquals(atimeFile, hdfs.getFileStatus(file).getAccessTime());
      assertEquals(mtimeDir, hdfs.getFileStatus(dir).getModificationTime());
      assertEquals(mtimeLink, hdfs.getFileLinkStatus(link).getModificationTime());
      assertEquals(atimeLink, hdfs.getFileLinkStatus(link).getAccessTime());
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }
  
  /**
   * In this test case, I have created an image with a file having
   * preferredblockSize = 0. We are trying to read this image (since file with
   * preferredblockSize = 0 was allowed pre 2.1.0-beta version. The namenode 
   * after 2.6 version will not be able to read this particular file.
   * See HDFS-7788 for more information.
   * @throws Exception
   */
  @Test
  public void testZeroBlockSize() throws Exception {
    final Configuration conf = new HdfsConfiguration();
    String tarFile = System.getProperty("test.cache.data", "build/test/cache")
      + "/" + HADOOP_2_7_ZER0_BLOCK_SIZE_TGZ;
    String testDir = PathUtils.getTestDirName(getClass());
    File dfsDir = new File(testDir, "image-with-zero-block-size");
    if (dfsDir.exists() && !FileUtil.fullyDelete(dfsDir)) {
      throw new IOException("Could not delete dfs directory '" + dfsDir + "'");
    }
    FileUtil.unTar(new File(tarFile), new File(testDir));
    File nameDir = new File(dfsDir, "name");
    GenericTestUtils.assertExists(nameDir);
    conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY, 
        nameDir.getAbsolutePath());
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1)
        .format(false)
        .manageDataDfsDirs(false)
        .manageNameDfsDirs(false)
        .waitSafeMode(false).startupOption(StartupOption.UPGRADE)
        .build();
    try {
      FileSystem fs = cluster.getFileSystem();
      Path testPath = new Path("/tmp/zeroBlockFile");
      assertTrue("File /tmp/zeroBlockFile doesn't exist ", fs.exists(testPath));
      assertTrue("Name node didn't come up", cluster.isNameNodeUp(0));
    } finally {
      cluster.shutdown();
      //Clean up
      FileUtil.fullyDelete(dfsDir);
    }
  }

  /**
   * Ensure that FSImage supports BlockGroup.
   */
  @Test
  public void testSupportBlockGroup() throws IOException {
    final short GROUP_SIZE = HdfsConstants.NUM_DATA_BLOCKS +
        HdfsConstants.NUM_PARITY_BLOCKS;
    final int BLOCK_SIZE = 8 * 1024 * 1024;
    Configuration conf = new HdfsConfiguration();
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, BLOCK_SIZE);
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(GROUP_SIZE)
          .build();
      cluster.waitActive();
      DistributedFileSystem fs = cluster.getFileSystem();
      fs.getClient().getNamenode().createErasureCodingZone("/");
      Path file = new Path("/striped");
      FSDataOutputStream out = fs.create(file);
      byte[] bytes = DFSTestUtil.generateSequentialBytes(0, BLOCK_SIZE);
      out.write(bytes);
      out.close();

      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

      cluster.restartNameNodes();
      fs = cluster.getFileSystem();
      assertTrue(fs.exists(file));

      // check the information of striped blocks
      FSNamesystem fsn = cluster.getNamesystem();
      INodeFile inode = fsn.dir.getINode(file.toString()).asFile();
      FileWithStripedBlocksFeature sb = inode.getStripedBlocksFeature();
      assertNotNull(sb);
      BlockInfoStriped[] blks = sb.getBlocks();
      assertEquals(1, blks.length);
      assertTrue(blks[0].isStriped());
      assertEquals(HdfsConstants.NUM_DATA_BLOCKS, blks[0].getDataBlockNum());
      assertEquals(HdfsConstants.NUM_PARITY_BLOCKS, blks[0].getParityBlockNum());
    } finally {
      cluster.shutdown();
    }
  }
}
