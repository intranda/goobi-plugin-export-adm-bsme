#!/usr/bin/env python3

# This script extracts eip files in a source directory to a target directory, renames a specific subdirectory and moves the original eip files

import os
import shutil
import sys
from argparse import ArgumentParser
from pathlib import Path


def extract_and_rename_eip(source_dir, target_dir):
    # Check if source directory exists
    if not os.path.isdir(source_dir):
        print("Source directory does not exist")
        exit(1)

    # Create target directory if it does not exist
    os.makedirs(target_dir, exist_ok=True)

    if source_dir.name.endswith("_master"):
        raw_directory = Path.joinpath(
            source_dir.parent, source_dir.name.replace("_master", "_raw"))
        os.makedirs(raw_directory, exist_ok=True)

    for filename in os.listdir(source_dir):
        try:
            if filename.endswith(".eip"):
                file_path = os.path.join(source_dir, filename)
                if os.path.isfile(file_path):
                    # Unzip eip to target directory
                    unzip_dir = os.path.join(
                        target_dir, os.path.splitext(filename)[0])
                    shutil.unpack_archive(filename=file_path,
                                        extract_dir=unzip_dir, format="zip")

                    capture_one_dir = os.path.join(unzip_dir, "CaptureOne")
                    settings_old = os.path.join(capture_one_dir, "Settings153")
                    settings_new = os.path.join(capture_one_dir, "Settings131")

                    # Check if CaptureOne directory exists
                    if not os.path.isdir(capture_one_dir):
                        print(f"{capture_one_dir} does not exist")
                        exit(1)

                    # Rename setting directory to matching version for cope
                    if os.path.isdir(settings_old):
                        os.rename(settings_old, settings_new)

                os.rename(file_path, Path.joinpath(raw_directory, filename))
        except Exception as e:
            print(f'An error occurred while processing file "{filename}":\n\t{e}', file=sys.stderr)
            exit(1)

def get_parser():
    """provides argument parser"""
    parser = ArgumentParser()
    parser.add_argument("-s", "--source", dest="source",
                        required=True, type=str, help="Source directory")
    parser.add_argument("-t", "--target", dest="target",
                        required=True, type=str, help="Target directory")
    return parser


if __name__ == "__main__":
    parser = get_parser()
    args = parser.parse_args()

    extract_and_rename_eip(Path(args.source), Path(args.target))
