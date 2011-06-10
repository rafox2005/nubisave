'''
Created on 08.04.2011

@author: joe
'''
import os.path

class StoreAccessError(Exception):
    def __init__(self, msg):
        super(StoreAccessError, self).__init__(msg)
class DateParseError(StoreAccessError):
    def __init__(self, msg):
        super(DateParseError, self).__init__(msg)
class RetrieveMetadataError(StoreAccessError):
    def __init__(self, path, msg):
        super(RetrieveMetadataError, self).__init__("Could not retrieve metadata for "+path+"\nDescription: "+msg)
class NoSuchFilesytemObjectError(StoreAccessError):
    def __init__(self, path):
        super(NoSuchFilesytemObjectError, self).__init__("%s does not exist." % path)
class InvalidPathValueError(ValueError):
    def __init__(self, path):
        super(InvalidPathValueError, self).__init__(path+" "+"is no valid path!!") 
class NoRemoteFileobjectNameGiven(StoreAccessError):
    def __init__(self, msg):
        super(DateParseError, self).__init__(msg)
    
class Store(object):
    def _is_valid_path(self, path):
        return path[0] == "/";
    
    def _raise_error_if_invalid_path(self, path):
        if not self._is_valid_path(path):
            raise InvalidPathValueError(path)
        
    def get_name(self):
        raise NotImplementedError()
    
    def get_file(self, path_to_file):
        raise NotImplementedError()
    
    def store_file(self, path_to_file, dest_dir="/", remote_file_name = None):
        fileobject = open(path_to_file)
        if not remote_file_name:
            remote_file_name = os.path.basename(path_to_file)
        self.store_fileobject(fileobject, dest_dir + "/" + remote_file_name)
        
    def store_fileobject(self, fileobject, path):
        raise NotImplementedError()
            
    def delete(self, path):
        raise NotImplementedError()
          
    def account_info(self):
        raise NotImplementedError()
    
    def get_free_space(self):
        return self.get_overall_space()-self.get_used_space()
    
    def get_overall_space(self):
        raise NotImplementedError()
    
    def get_used_space(self):
        raise NotImplementedError()

    def create_directory(self, directory):
        raise NotImplementedError()
        
    def duplicate(self, path_to_src, path_to_dest):
        raise NotImplementedError()
        
    def move(self, path_to_src, path_to_dest):
        self.duplicate(path_to_src, path_to_dest)
        self.delete(path_to_src)
 
    def get_modified(self, path):
        resp = self._get_metadata(path)
        return resp['modified']  
    
    def get_directory_listing(self, directory):
        raise NotImplementedError()
    
    def get_bytes(self, path):
        resp = self._get_metadata(path)
        return resp['bytes']
    
    def exists(self, path):
        try:
            self._get_metadata(path)
            return True
        except NoSuchFilesytemObjectError:
            return False;
    
    def _get_metadata(self, path):
        raise NotImplementedError()

    def is_dir(self, path):
        resp = self._get_metadata(path)
        return resp['is_dir']
    
    def flush(self):
        pass
