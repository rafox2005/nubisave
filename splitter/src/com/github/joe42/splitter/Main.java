package com.github.joe42.splitter;

import org.apache.commons.logging.LogFactory;

import com.github.joe42.splitter.backend.StorageServicesMgr;

import fuse.FuseMount;

public class Main {
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
			StorageServicesMgr storageServiceMgr = new StorageServicesMgr(args[3]);
			CauchyReedSolomonSplitter splitter = new CauchyReedSolomonSplitter(storageServiceMgr.getServices()); 
			FuseMount.mount(fuseArgs, new ConfigurableFuseBox(splitter, storageServiceMgr), LogFactory.getLog("javafs"));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
