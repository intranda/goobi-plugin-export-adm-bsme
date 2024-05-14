package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import de.sub.goobi.helper.StorageProvider;
import ugh.dl.DocStruct;
import ugh.dl.Metadata;

public class AdmBsmeExportHelper {

    /**
     * copy files to target directory
     * 
     * @param ds
     * @throws IOException
     */
    public static void copyFolderContent(String sourcefolder, String ext, Map<String, String> fileMap, String targetFolder) throws IOException {
        for (Path pathIn : StorageProvider.getInstance().listFiles(sourcefolder)) {
            String fileIn = pathIn.getFileName().toString();
            fileIn = fileIn.substring(0, fileIn.indexOf("."));
            String fileOut = fileMap.get(fileIn);
            Path pathOut = Paths.get(targetFolder, fileOut + "." + ext);
            // log.debug(pathIn + " ---> " + pathOut);
            StorageProvider.getInstance().copyFile(pathIn, pathOut);
        }
    }

    /**
     * get a specific metadata from given docstruct
     * 
     * @param ds
     */
    public static String getMetdata(DocStruct ds, String field) {
        // run through all metadata to find the right one
        for (Metadata md : ds.getAllMetadata()) {
            if (md.getType().getName().equals(field)) {
                return md.getValue();
            }
        }
        return "";
    }

    /**
     * extremely simple translation method to convert German issue labes into pseudo english labels
     *
     * @param value
     * @return
     */
    public static String getTranslatedIssueLabels(String value) {
        String copy = value;
        copy = copy.replace("Ausgabe vom", "");
        copy = copy.replace("Issue from", "");
        return copy;
    }

    /**
     * get English part of a metadata that is separated from Arabic part
     *
     * @param value
     * @return
     */
    public static String getEnglishPartOfString(String value) {
        String copy = value.replace("–", "-");
        if (copy.contains("-")) {
            copy = StringUtils.substringAfter(copy, "-").trim();
        }
        return copy;
    }

    /**
     * get the language from metadata
     * 
     * @param ds
     */
    public static String getLanguageFullname(DocStruct ds, String field) {
        String lang = getMetdata(ds, field);
        switch (lang) {
            case "Arabic":
                return "عربي – Arabic";
            case "ara":
                return "عربي – Arabic";
            case "English":
                return "انجليزي – English";
            case "eng":
                return "انجليزي – English";
            case "ger":
                return "German";
        }
        return lang;
    }
}
