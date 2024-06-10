
# COPE scripts

## EIP extraction

The script `eip_prepare_for_cope.py` extracts EIP files in a source directory to a target directory. It renames a specific subdirectory (`CaptureOne/Settings153` to `CaptureOne/Settings131`) and moves the EIP files into the `_raw` image directory.

## Usage

```
usage: eip_prepare_for_cope.py [-h] -s SOURCE -t TARGET

options:
  -h, --help            show this help message and exit
  -s SOURCE, --source SOURCE
                        Source directory
  -t TARGET, --target TARGET
                        Target directory
```

in Goobi workflow this can be integrated in a script step like this:

```shell
/opt/digiverso/goobi/scripts/eip_prepare_for_cope.py -s {origpath} -t {origpath}
```

## COPE conversion

The script `cope_folder.py` converts IIQ files from the source directory and uses COPE to convert them to tiff files located in the target directory. Optionally the given resolution value will be used.

## Usage

```
usage: cope_folder.py [-h] -s SOURCE -t TARGET [-r RESOLUTION]

options:
  -h, --help            show this help message and exit
  -s SOURCE, --source SOURCE
  -t TARGET, --target TARGET
  -r RESOLUTION, --resolution RESOLUTION
```

The path to the cope binary can be given by an environment variable named `COPE_PATH`.

In Goobi workflow a script step in the external queue can be used to run the script on a Windows workernode like this:

```CMD
C:\Windows\py.exe D:\intranda\cope_folder.py -s "//mediaSMB-isilonArchive/GoobiMetadata/{s3_origpath}" -t "//mediaSMB-isilonArchive/GoobiMetadata/{s3_origpath}" -r "{process.File Resolution}"
```

