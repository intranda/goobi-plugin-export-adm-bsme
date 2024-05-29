#!/usr/bin/env python3

import logging
import os
import shutil
import subprocess
from argparse import ArgumentParser
from pathlib import Path

loglevel = (os.environ.get('PYTHON_LOGLEVEL') or "DEBUG").upper()
numeric_level = getattr(logging, loglevel.upper(), None)
logging.basicConfig(
    format='%(asctime)s %(levelname)-8s %(message)s', level=numeric_level)

file_name_pattern = "*.IIQ"
path_cope = (os.environ.get('COPE_PATH') or Path(
    "C:/cope.Win.13.1.15-name_cope131/COPE/Bin/COPE.exe"))

cope_options = ["-bits=8"]


def find_files(root_dir: Path, file_name_pattern: str) -> list[list]:
    """find files matching file_name_pattern in direct subdirectories of root_dir

    Args:
        root_dir (Path): top level directory to perform search in
        file_name_pattern (str): globbing expression to match for

    Returns:
        list[list]: a list of lists with the filename and the parent directory basename of matching files
    """
    found_files = []
    for file in Path(root_dir).glob("*/"+file_name_pattern):
        found_files.append([file, file.parent.stem])
    return (found_files)


def get_parser():
    """provides argument parser"""
    parser = ArgumentParser()
    parser.add_argument("-s", "--source", dest="source",
                        required=True, type=str)
    parser.add_argument("-t", "--target", dest="target",
                        required=True, type=str)
    parser.add_argument("-r", "--resolution", dest="resolution",
                        required=False, type=int)
    return parser


if __name__ == "__main__":
    parser = get_parser()
    args = parser.parse_args()

    sourcedir = Path(args.source)
    targetdir = Path(args.target)
    resolution = args.resolution

    logging.debug(f"path to cope: {path_cope}")
    logging.debug(f"source directory: {sourcedir}")
    logging.debug(f"target directory: {targetdir}")
    
    if resolution:
        logging.debug(f"resolution: {resolution}")
        cope_options.append(f"-resolution={resolution}")

    if not os.path.isdir(sourcedir):
        logging.error("source directory does not exist")
        exit(1)

    if not os.path.isdir(targetdir):
        os.mkdir(targetdir)
        logging.info("target directory created, as it did not exist")

    logging.info("start processing...")
    files = find_files(sourcedir, file_name_pattern)

    if not files:
        logging.info("no matching files found in source directory")

    for file, base in files:
        target = Path(targetdir) / (base + ".tif")
        logging.info(f"processing {file}")
        
        cope_command = [Path(path_cope), file, target] + cope_options
        
        logging.debug(f"running {cope_command}")

        # execute cope
        try:
            subprocess.run(cope_command, check=True)
        except FileNotFoundError:
            logging.error(f"cope executable was not found: {path_cope}")
            exit(1)
        except Exception as err:
            logging.error(f"Unexpected {err=}, {type(err)=}")
            raise

        # cope does not appear to make much use of exit codes, thus we check the existence of the expected tiff file here
        if not target.is_file():
            logging.error(f"tiff file expected but not found: {target}")
            exit(1)
        else:
            logging.debug(f"removing source package {target.with_suffix('')}")
            shutil.rmtree(target.with_suffix(''))
