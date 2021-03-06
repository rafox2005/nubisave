package com.github.joe42.splitter.backend;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.ini4j.Ini;


import com.github.joe42.splitter.util.file.FileUtil;

/**
 * Facade to add, remove, mount and unmount storages.
 * */
public class StorageServicesMgr { 
	private Mounter mounter;
	private String storages;
	private BackendServices services;

	public StorageServicesMgr(String storages){
		this.storages = storages;
		mounter = new Mounter(storages);
		services = new BackendServices();
	}
	/**
	 * Get the current services
	 * @return the Services object 
	 */
	public BackendServices getServices(){
		return services;
	}
	
	/**@return the folder with this mounter's mounted filesystems*/
	public String getStorages(){
		return mounter.getStorages();
	}	
	
	/**
	 * Execute the command given in mountOption's mounting section, which should mount a filesystem. 
	 * The command's parameters of the name mountpoint will be substituted by the path 
	 * this.getStorages()+"/"+uniqueServiceName. This is where the filesystem should be mounted to. 
	 * Further mountOptions may contain a parameter section with options, where each word in the command is substituted 
	 * by the value of the parameter option of the same name. 
	 * 
	 * Goes on to call {@link #configureService(String uniqueServiceName, Ini mountOptions) configureService}
	 * 
	 * The execution should result in a file system mounted so that its data can be accessed by the subdirectory called
	 * StorageService.DATA_DIR_NAME in the path where it should be mounted and a configuration StorageService.CONFIG_PATH, 
	 * also in the path, where the filesystem should be mounted.
	 * 
	 * Adds a new backend service to services, if mounting is successful.
	 * 
	 * @param uniqueServiceName unique name for the storage
	 * @param mountOptions an ini file with several mount options for the storage service to mount
	 * @return the path to the mountpoint if the file StorageService.CONFIG_PATH exists  within it after at most 10 seconds and null otherwise
	 * */
	public String mount(String uniqueServiceName, Ini mountOptions){
		BackendService service = new BackendService(storages, uniqueServiceName, mountOptions);
		mounter.mount(service);
		services.add(uniqueServiceName, service);
		return service.getPath();
	}
	
	/**
	 * @param uniqueServiceName
	 * @return true iff the service was unmounted successfully
	 */
	public boolean unmount(String uniqueServiceName){
		BackendService service = services.get(uniqueServiceName);
		boolean unmounted = mounter.unmount(service);
		if(unmounted) {
			services.remove(uniqueServiceName);
		}
		return unmounted;
	}
	
	/**
	 * @param uniqueServiceName
	 * @return true iff the storage denoted by uniqueServiceName is mounted
	 */
	public  boolean isMounted(String uniqueServiceName) {
		BackendService service = services.get(uniqueServiceName);
		if(service == null){
			return false;
		}
		return mounter.isStorageMounted(services.get(uniqueServiceName));
	}
	
	/**
	 * Configure a storage
	 * There can be a section splitter containing the following optional options to configure
	 * the use of the file system by splitter: 
	 * The isbackendmodule option decides whether the service 
	 * should be mounted in the special directory StorageService.HIDDEN_DIR_NAME. 
	 * To this end the mountpoint parameter is substituted by the path
	 * this.getStorages()+"/"+StorageService.HIDDEN_DIR_NAME+"/"+uniqueServiceName  instead.
	 * Choose a storage strategy for the splitter by setting the storagestrategy option to roundrobin or useallinparallel. 
	 * The fileparts option is an integer which may be used by a storage strategy to choose how intensely a storage shall be used.
	 * Commonly, fileparts is used to determine how many file parts are distributed to the store after splitting up a file.  
	 * 
	 * All registered observers are notified of the changes.
	 * 
	 * @param uniqueServiceName
	 * @param mountOptions an ini file with several mount options for the storage service to mount
	 */
	public void configureService(String uniqueServiceName, Ini mountOptions){
		services.configure(uniqueServiceName, mountOptions);
	}
}
