package com.github.joe42.splitter;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import jdbm.RecordManager;
import jdbm.RecordManagerFactory;
import jdbm.helper.FastIterator;
import jdbm.htree.HTree;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.util.encoders.Hex;
import org.jigdfs.ida.base.InformationDispersalCodec;
import org.jigdfs.ida.base.InformationDispersalDecoder;
import org.jigdfs.ida.base.InformationDispersalEncoder;
import org.jigdfs.ida.cauchyreedsolomon.CauchyInformationDispersalCodec;

import com.github.joe42.splitter.util.StringUtil;
import com.github.joe42.splitter.util.file.ConcurrentMultipleFileHandler;
import com.github.joe42.splitter.util.file.MultipleFileHandler;
import com.github.joe42.splitter.util.file.RandomAccessTemporaryFileChannel;
import com.github.joe42.splitter.util.file.RandomAccessTemporaryFileChannels;
import com.github.joe42.splitter.vtf.Entry;
import com.github.joe42.splitter.vtf.FileEntry;
import com.github.joe42.splitter.vtf.FolderEntry;
import com.github.joe42.splitter.vtf.VirtualFileContainer;

import fuse.FuseException;
import fuse.FuseFtype;
import fuse.FuseMount;
import fuse.FuseStatfs;
import fuse.compat.Filesystem1;
import fuse.compat.FuseDirEnt;
import fuse.compat.FuseStat;

//TODO: add copyright
//Parallel read: done
//Fixed: write appended data; or wrote nonsence.
//<1238
//overwrite existing files

public class Splitter implements Filesystem1 {
	private static final Logger  log = Logger.getLogger("Splitter");

	private static final int blockSize = 512;

	private static int MAX_FILE_FRAGMENTS;

	private static int MAX_FILE_FRAGMENTS_NEEDED;
	private RecordManager recman;
	private HTree filemap;
	private HTree dirmap;
	private RandomAccessTemporaryFileChannels tempFiles = new RandomAccessTemporaryFileChannels();

	private MultipleFileHandler multi_file_handler;

	private FuseStatfs statfs;

	protected String storages;

	protected int redundancy;

	private RandomAccessTemporaryFileChannel tempReadChannel;

	private VirtualFileContainer virtualFileContainer;

	public Splitter(String storages, int redundancy) throws IOException {
		// .config###/
		// aufruf splitter_mount.sh mountordner ordner_mit_storage_ordner
		// "redundancy level in percent 0-100"
		PropertyConfigurator.configure("log4j.properties");
		
		this.storages = storages;
		this.redundancy = redundancy;
		multi_file_handler = new ConcurrentMultipleFileHandler();

		Properties props = new Properties();
		recman = RecordManagerFactory.createRecordManager("splitter", props);
		// create or load
		filemap = loadPersistentMap(recman, "filemap");
		dirmap = loadPersistentMap(recman, "dirmap");

		try {
			dirmap.put("/", new FolderEntry());
			recman.commit();
		} catch (IOException e) {
			throw new IOException("IO Exception on accessing metadata");
		}

		log.debug("dirmap size:" + ((Entry) dirmap.get("/")).size);
	}

	private HTree loadPersistentMap(RecordManager recman, String mapName)
			throws IOException {
		long recid = recman.getNamedObject(mapName);
		HTree ret;
		if (recid != 0) {
			ret = HTree.load(recman, recid);
		} else {
			ret = HTree.createInstance(recman);
			recman.setNamedObject(mapName, ret.getRecid());
		}
		return ret;
	}

	public void chmod(String path, int mode) throws FuseException {
		throw new FuseException("Read Only").initErrno(FuseException.EACCES);
	}

	public void chown(String path, int uid, int gid) throws FuseException {
		throw new FuseException("Read Only").initErrno(FuseException.EACCES);
	}

	// mknod is not called because of unsupportet op exception here
	public FuseStat getattr(String path) throws FuseException {
		FuseStat stat = new FuseStat();
		Entry entry = null;
		/*
		 * if(path.startsWith("/.config###")){ if(path.equals("/.config###")){
		 * entry = new FolderEntry(); stat.mode = FuseFtype.TYPE_DIR | 0755; }
		 * if() }
		 */
		try {
			if (filemap.get(path) != null) {
				entry = (Entry) filemap.get(path);
				stat.mode = FuseFtype.TYPE_FILE | 0755;
			} else if (dirmap.get(path) != null) {
				entry = (Entry) dirmap.get(path);
				stat.mode = FuseFtype.TYPE_DIR | 0755;
			}
		} catch (IOException e) {
			throw new FuseException("IO Exception on reading metadata")
					.initErrno(FuseException.EIO);
		}
		if (entry == null)
			throw new FuseException("No Such Entry")
					.initErrno(FuseException.ENOENT);
		//TODO: tidy up
		stat.nlink = entry.nlink;
		stat.uid = entry.uid;
		stat.gid = entry.gid;
		stat.size = entry.size;
		stat.atime = entry.atime;
		stat.mtime = entry.mtime;
		stat.ctime = entry.ctime;
		stat.blocks = (int) ((stat.size + 511L) / 512L);

		return stat;
	}

	public FuseDirEnt[] getdir(String path) throws FuseException {
		//TODO: tidy up
		FastIterator pathes;
		try {
			if (filemap.get(path) != null)
				throw new FuseException("Not A Directory")
						.initErrno(FuseException.ENOTDIR);
			if (dirmap.get(path) == null)
				throw new FuseException("No Such Entry")
						.initErrno(FuseException.ENOENT);
			pathes = filemap.keys();
		} catch (IOException e) {
			throw new FuseException("IO Exception on accessing metadata")
					.initErrno(FuseException.EIO);
		}
		String fileName, dirName;
		List<FuseDirEnt> dirEntries = new ArrayList<FuseDirEnt>();
		while ((fileName = (String) pathes.next()) != null) {
			if (fileName.startsWith(path)
					&& fileName.indexOf("/", path.length() - 1) == path
							.length() - 1) {
				FuseDirEnt dirEntry = new FuseDirEnt();
				dirEntry.name = fileName.substring(path.length());
				dirEntry.mode = FuseFtype.TYPE_FILE;
				dirEntries.add(dirEntry);
			}
		}
		try {
			pathes = dirmap.keys();
		} catch (IOException e) {
			throw new FuseException("IO Exception on accessing metadata")
					.initErrno(FuseException.EIO);
		}
		while ((dirName = (String) pathes.next()) != null) {
			if (dirName.startsWith(path)
					&& dirName.indexOf("/", path.length() - 1) == path.length() - 1
					&& !dirName.equals(path)) {
				FuseDirEnt dirEntry = new FuseDirEnt();
				dirEntry.name = dirName.substring(path.length());
				dirEntry.mode = FuseFtype.TYPE_DIR;
				dirEntries.add(dirEntry);
			}
		}
		FuseDirEnt[] ret = new FuseDirEnt[dirEntries.size() + 2];

		int i = 0;
		FuseDirEnt dirEntry = new FuseDirEnt();
		dirEntry.name = ".";
		dirEntry.mode = FuseFtype.TYPE_DIR;
		ret[i++] = dirEntry;
		dirEntry = new FuseDirEnt();
		dirEntry.name = "..";
		dirEntry.mode = FuseFtype.TYPE_DIR;
		ret[i++] = dirEntry;
		for (FuseDirEnt dirEntryIter : dirEntries) {
			ret[i++] = dirEntryIter;
		}
		return ret;
	}

	public void link(String from, String to) throws FuseException {
		throw new FuseException("Read Only").initErrno(FuseException.EACCES);
	}

	public void mkdir(String path, int mode) throws FuseException {
		try {
			dirmap.put(path, new FolderEntry());
			recman.commit();
		} catch (IOException e) {
			throw new FuseException("IO Exception on accessing metadata")
					.initErrno(FuseException.EIO);
		}
	}

	public void mknod(String path, int mode, int rdev) throws FuseException {

		try {
			tempFiles.putNewFileChannel(path);
			filemap.put(path, new FileEntry());
			recman.commit();
		} catch (IOException e) {
			throw new FuseException("IO Exception on accessing metadata")
					.initErrno(FuseException.EIO);
		}
		splitFile(path); //kann man nicht rausnehmen (vielleicht doch; Dropbox 400 Error bei leeren Dateien ist gefixed)
	}

	public void open(String path, int flags) throws FuseException {
		log.debug("opened: " + path);
		// ZipEntry entry = getFileZipEntry(path);

		// if (flags == O_WRONLY || flags == O_RDWR)
		// throw new FuseException("Read Only").initErrno(FuseException.EACCES);
	}

	public void rename(String from, String to) throws FuseException {
		if (from.equals(to))
			return;
		Entry entry = null;
		HTree map = null;
		try {
			if (filemap.get(from) != null) {
				map = filemap;
			} else if (dirmap.get(from) != null) {
				map = dirmap;
			}
			if (map == null)
				throw new FuseException("No Such Entry")
						.initErrno(FuseException.ENOENT);
			entry = (Entry) map.get(from);
			map.put(to, entry);
			map.remove(from);
			recman.commit();
		} catch (IOException e) {
			throw new FuseException("IO Exception on reading metadata")
					.initErrno(FuseException.EIO);
		}
	}

	public void rmdir(String path) throws FuseException {
		Entry dirEntry = null;
		try {
			dirEntry = (FolderEntry) dirmap.get(path);
			if (dirEntry == null)
				throw new FuseException("No Such Entry")
						.initErrno(FuseException.ENOENT);
			new File(path).delete();
			dirmap.remove(path);
			recman.commit();
		} catch (IOException e) {
			throw new FuseException("IO Exception on accessing metadata")
					.initErrno(FuseException.EIO);
		}
	}

	public FuseStatfs statfs() throws FuseException {
		int files = 0;
		int dirs = 0;
		int blocks = 0;
		FastIterator iter;
		try {
			iter = filemap.keys();
			String path = (String) iter.next();
			while (path != null) {
				files++;
				blocks += (((Entry) filemap.get(path)).size + blockSize - 1)
						/ blockSize;
				path = (String) iter.next();
			}
			iter = dirmap.keys();
			path = (String) iter.next();
			while (path != null) {
				dirs++;
				blocks += (((Entry) dirmap.get(path)).size + blockSize - 1)
						/ blockSize;
				path = (String) iter.next();
			}

		} catch (IOException e) {
			throw new FuseException("IO Exception on accessing metadata")
					.initErrno(FuseException.EIO);
		}
		statfs = new FuseStatfs();
		statfs.blocks = blocks;
		statfs.blockSize = blockSize;
		statfs.blocksFree = 0;
		statfs.files = files + dirs;
		statfs.filesFree = 0;
		statfs.namelen = 2048;

		log.debug(files + " files, " + dirs + " directories, " + blocks
				+ " blocks (" + blockSize + " byte/block).");
		return statfs;
	}

	public void symlink(String from, String to) throws FuseException {
		throw new FuseException("Read Only").initErrno(FuseException.EOPNOTSUPP);
	}

	public void truncate(String path, long size) throws FuseException {
		try {
			if (filemap.get(path) != null) {
				tempFiles.put(path, glueFilesTogether(path));
				try {
					tempFiles.getFileChannel(path).truncate(size);
				} catch (FileNotFoundException e) {
					throw new FuseException("No Such Entry")
							.initErrno(FuseException.ENOENT);
				} catch (IOException e) {
					throw new FuseException("IO Exception on truncating file")
							.initErrno(FuseException.EIO);
				}
			}
		} catch (IOException e) {
			throw new FuseException("IO Exception on accessing metadata")
					.initErrno(FuseException.EIO);
		}
	}

	public void unlink(String path) throws FuseException {
		try {
			FileEntry entry = (FileEntry) filemap.get(path);
			int del_cnt = 0;
			for (String fragmentName : entry.fragment_names) {
				if (new File(fragmentName).delete()) {
					del_cnt++;
				}
			}
			int possibly_existing_filenr = MAX_FILE_FRAGMENTS - del_cnt;
			if (possibly_existing_filenr >= MAX_FILE_FRAGMENTS_NEEDED) {
				throw new FuseException("IO Exception on deleting file")
						.initErrno(FuseException.EACCES);
			}
			filemap.remove(path);
			recman.commit();
		} catch (IOException e) {
			throw new FuseException("IO Exception on reading metadata")
					.initErrno(FuseException.EIO);
		}
	}

	public void utime(String path, int atime, int mtime) throws FuseException {
		// noop
	}

	public String readlink(String path) throws FuseException {
		throw new FuseException("Not a link").initErrno(FuseException.ENOENT);
	}

	public void write(String path, ByteBuffer buf, long offset)
			throws FuseException {
		try {
			if (tempFiles.getFileChannel(path) == null) {
				System.out.println("glue? ");
				tempFiles.put(path, glueFilesTogether(path));
				System.out.println("glued! ");
			}
			FileChannel wChannel = tempFiles.getFileChannel(path);
			//wChannel.position(offset);
			wChannel.write(buf,offset);
		} catch (IOException e) {
			throw new FuseException("IO Exception")
					.initErrno(FuseException.EIO);
		}
	}

	private List<String> getFragmentStores() {
		List<String> ret = new ArrayList<String>();
		File f = new File(storages);
		String[] folders = f.list();
		ret.clear();
		if (folders == null) {
			System.out.println(storages + " is not a directory!");
		} else {
			for (int i = 0; i < folders.length; i++) {
				ret.add(f.getAbsolutePath()+"/"+folders[i]);
			}
		}
		return ret;
	}

	private void splitFile(String path) throws FuseException {
		if (tempFiles.getFileChannel(path) == null) {
			throw new FuseException("IO Exception - nothing to split")
					.initErrno(FuseException.EIO);
		}
		int nr_of_file_parts_successfully_stored = 0;
		HashMap<String, byte[]> fileParts = new HashMap<String, byte[]>();
		List<String> fragmentStores = getFragmentStores();
		MAX_FILE_FRAGMENTS = fragmentStores.size();
		MAX_FILE_FRAGMENTS_NEEDED = (int) (MAX_FILE_FRAGMENTS *(100-redundancy) /100f); //100% redundancy -> only one file is enough to restore everything
		if(MAX_FILE_FRAGMENTS_NEEDED <1){
			MAX_FILE_FRAGMENTS_NEEDED=1;
		}
		FileChannel temp = tempFiles.getFileChannel(path);
		FileEntry fileEntry = null;
		try {
			fileEntry = (FileEntry) filemap.get(path);
		} catch (IOException e1) {
			throw new FuseException("IO Exception on accessing metadata")
					.initErrno(FuseException.EIO);
		}
		String fragment_name;
		String uniquePath;
		if (fileEntry.fragment_names.isEmpty()) {
			uniquePath = StringUtil.getUniqueAsciiString(path);
			for (int fragment_nr = 0; fragment_nr < MAX_FILE_FRAGMENTS; fragment_nr++) {
				fragment_name = fragmentStores.get(fragment_nr) + uniquePath
						+ '#' + fragment_nr;
				fileEntry.fragment_names.add(fragment_name);
			}
		}
		Digest digestFunc = new SHA256Digest();
		byte[] digestByteArray = new byte[digestFunc.getDigestSize()];
		String hexString = null;
		InformationDispersalCodec crsidacodec;
		InformationDispersalEncoder encoder;
		try {
			crsidacodec = new CauchyInformationDispersalCodec(
					MAX_FILE_FRAGMENTS, MAX_FILE_FRAGMENTS_NEEDED, 1);
			encoder = crsidacodec.getEncoder();
		} catch (Exception e) {
			e.printStackTrace();
			throw new FuseException("IDA could not be initialized")
					.initErrno(FuseException.EACCES);
		}
		try {
			byte[] arr = new byte[(int) temp.size()];
			temp.read(ByteBuffer.wrap(arr));
			digestFunc.update(arr, 0, arr.length);
			digestFunc.doFinal(digestByteArray, 0);
			List<byte[]> result = encoder.process(arr);

			for (int fragment_nr = 0; fragment_nr < MAX_FILE_FRAGMENTS; fragment_nr++) {

				fragment_name = fileEntry.fragment_names.get(fragment_nr);
				log.debug("write: " + fragment_name);
				fileEntry.fragment_names.add(fragment_name);
				byte[] b = result.get(fragment_nr);
				digestFunc.reset();
				digestFunc.update(b, 0, b.length);
				digestFunc.doFinal(digestByteArray, 0);

				hexString = new String(Hex.encode(digestByteArray));
				fileParts.put(fragment_name, b);
			}
			nr_of_file_parts_successfully_stored = multi_file_handler.writeFilesAsByteArrays(fileParts);
			fileEntry.size = (int) temp.size();

			filemap.put(path, fileEntry);
			recman.commit();
		} catch (Exception e) {
			e.printStackTrace();
			throw new FuseException("IO error: " + e.toString(), e)
					.initErrno(FuseException.EIO);
		}

		//if (log.isDebugEnabled())
		  log.debug("nr_of_file_parts_successfully_stored: "+nr_of_file_parts_successfully_stored+" - MAX_FILE_FRAGMENTS_NEEDED "+MAX_FILE_FRAGMENTS_NEEDED);
		if (nr_of_file_parts_successfully_stored < MAX_FILE_FRAGMENTS_NEEDED) {
			throw new FuseException(
					"IO error: Not enough file parts could be stored.")
					.initErrno(FuseException.EIO);
		}
	}

	private RandomAccessTemporaryFileChannel glueFilesTogether(String path) throws FuseException {
		RandomAccessTemporaryFileChannel ret = null;
		List<byte[]> receivedFileSegments = new ArrayList<byte[]>();
		Digest digestFunc = new SHA256Digest();
		byte[] digestByteArray = new byte[digestFunc.getDigestSize()];
		InformationDispersalCodec crsidacodec;
		InformationDispersalDecoder decoder;
		try {
			crsidacodec = new CauchyInformationDispersalCodec(
					MAX_FILE_FRAGMENTS, MAX_FILE_FRAGMENTS_NEEDED, 1);
			decoder = crsidacodec.getDecoder();
		} catch (Exception e) {
			e.printStackTrace();
			throw new FuseException("IDA could not be initialized")
					.initErrno(FuseException.EACCES);
		}
		String hexString = null;
		log.info("glue ");
		int readBytes;
		try {
			ret = new RandomAccessTemporaryFileChannel();
			int validSegment = 0;
			List<String> fragmentNames = ((FileEntry) filemap.get(path)).fragment_names;

			List<byte[]> segmentBuffers = multi_file_handler
					.getFilesAsByteArrays(fragmentNames.toArray(new String[0]), MAX_FILE_FRAGMENTS_NEEDED);
			for (byte[] segmentBuffer : segmentBuffers) {
				//log.info("glue " + new String(segmentBuffer));

				digestFunc.reset();

				digestFunc.update(segmentBuffer, 0, segmentBuffer.length);

				digestFunc.doFinal(digestByteArray, 0);

				hexString = new String(Hex.encode(digestByteArray));

				/*
				 * if (!hexString.equals(file_fragment.getName())) {
				 * log.error("this file segment is invalid! " +
				 * file_fragment.getName() + " <> " + hexString); } else {
				 */
				receivedFileSegments.add(segmentBuffer);
				validSegment++;

				if (validSegment >= MAX_FILE_FRAGMENTS_NEEDED) {
					break;
				}
				// }
			}
			byte[] recoveredFile = decoder.process(receivedFileSegments);

			digestFunc.reset();

			digestFunc.update(recoveredFile, 0, recoveredFile.length);

			digestFunc.doFinal(digestByteArray, 0);

			ret.getChannel().write(ByteBuffer.wrap(recoveredFile));

		} catch (Exception e) {
			e.printStackTrace();
			throw new FuseException("IO error", e).initErrno(FuseException.EIO);
		}
		return ret;
	}

	public void read(String path, ByteBuffer buf, long offset)
			throws FuseException {
		try {
			if (tempReadChannel == null) {
				tempReadChannel = glueFilesTogether(path);
			}
		tempReadChannel.getChannel().read(buf, offset);
		} catch (IOException e) {
			throw new FuseException("IO Exception")
					.initErrno(FuseException.EIO);
		}
		if (log.isDebugEnabled())
			log.debug("read " + buf.position() + "/" + buf.capacity()
					+ " requested bytes");
	}

	public void release(String path, int flags) throws FuseException {
		if (tempFiles.getFileChannel(path) != null) {
			splitFile(path);
			tempFiles.delete(path);
		}
		if (tempReadChannel != null) {
			tempReadChannel.delete();
			tempReadChannel = null;
		}
	}

	//
	// Java entry point

	public static void main(String[] args) {
		if (args.length < 4) {
			System.out
					.println("Must specify mountpoint folder_with_storage_mountpoints");
			System.exit(-1);
		}

		String fuseArgs[] = new String[args.length-1];
		System.arraycopy(args, 0, fuseArgs, 0, fuseArgs.length);
		// System.out.println(fuseArgs[0]);
		try {
			FuseMount.mount(fuseArgs, new ConfigurableSplitter(args[3]));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}