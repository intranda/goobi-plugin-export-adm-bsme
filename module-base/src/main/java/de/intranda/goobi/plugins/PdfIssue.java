package de.intranda.goobi.plugins;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

public class PdfIssue {
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private String folder;
    @Getter
    @Setter
    private List<String> files = new ArrayList<>();
    @Getter
    @Setter
    private List<File> pdfFiles = new ArrayList<>();

    public Map<String, String> getAsMap() {
        StringBuilder sbi = new StringBuilder();
        StringBuilder sba = new StringBuilder();
        for (String f : files) {
            if (files.indexOf(f) > 0) {
                sbi.append("$");
                sba.append("$");
            }
            sbi.append("file://");
            sbi.append(folder);
            sbi.append(f);
            sbi.append(".tif");

            sba.append("file://");
            sba.append(folder);
            sba.append(f);
            sba.append(".xml");
        }

        Map<String, String> map = Map.of("images", sbi.toString(),
                "altos", sba.toString());
        return map;
    }
}
