package de.intranda.goobi.plugins.exporters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.goobi.beans.Process;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.intranda.goobi.plugins.AdmBsmeExportHelper;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.unigoettingen.sub.commons.contentlib.exceptions.ImageManagerException;
import de.unigoettingen.sub.commons.contentlib.imagelib.ImageInterpreter;
import de.unigoettingen.sub.commons.contentlib.imagelib.ImageManager;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Prefs;
import ugh.dl.Reference;

import static de.intranda.goobi.plugins.AdmBsmeExportHelper.createTechnicalNotesElementFromRelevantJournalEntries;

@PluginImplementation
@Log4j2
public class SlideExporter {

    private XMLConfiguration config;
    private Process process;
    private Prefs prefs;
    private DigitalDocument dd;
    private String targetFolder;

    // keep a list of all image files as they need to be renamed
    private Map<String, String> fileMap;
    private VariableReplacer vr;

    @Getter
    private List<String> problems;

    /**
     * Constructor
     * 
     * @param config
     * @param process
     * @param prefs
     * @param dd
     */
    public SlideExporter(XMLConfiguration config, Process process, Prefs prefs, DigitalDocument dd) {
        this.config = config;
        config.setExpressionEngine(new XPathExpressionEngine());
        this.process = process;
        this.prefs = prefs;
        this.dd = dd;
        targetFolder = config.getString("targetDirectorySlides", "/opt/digiverso/goobi/output/");
    }

    /**
     * Do the actual export for a newspaper volume
     * 
     * @param process
     * @param destination
     * @return
     */
    public boolean startExport() {
        vr = new VariableReplacer(dd, prefs, process, null);
        problems = new ArrayList<>();
        fileMap = new HashMap<String, String>();
        log.debug("Export directory for AdmBsmeExportPlugin: " + targetFolder);
        DocStruct topStruct = dd.getLogicalDocStruct();

        // prepare xml document
        Document doc = new Document();
        doc.setRootElement(new Element("image"));

        // add volume information
        Element info = new Element("SlideInfo");
        doc.getRootElement().addContent(info);
        String identifier = AdmBsmeExportHelper.getMetdata(topStruct, config.getString("/metadata/identifier"));

        String rightsToUse = vr.replace(config.getString("/rightsToUse"));
        String rightsDetails = vr.replace(config.getString("/rightsDetails"));
        String source = vr.replace(config.getString("/source"));
        String mediaType = vr.replace(config.getString("/mediaType"));
        //String mediaGroup = vr.replace(config.getString("/mediaGroup"));
        String sourceOrganisation = vr.replace(config.getString("/sourceOrganisation"));
        String eventDate = vr.replace(config.getString("/eventDate"));
        String eventName = vr.replace(config.getString("/eventName"));
        String subjectEng = vr.replace(config.getString("/subjectEnglish"));
        String subjectAra = vr.replace(config.getString("/subjectArabic"));
        String photographer = vr.replace(config.getString("/photographer"));
        String personsInImage = vr.replace(config.getString("/personsInImage"));
        String locations = vr.replace(config.getString("/locations"));
        String description = vr.replace(config.getString("/description"));
        //String editorInChief = vr.replace(config.getString("/editorInChief"));
        String format = vr.replace(config.getString("/format"));

        info.addContent(new Element("Rights_to_Use").setText(rightsToUse));
        info.addContent(new Element("Right_Details").setText(rightsDetails));
        info.addContent(new Element("Media_Source").setText(source));
        info.addContent(new Element("Media_type").setText(mediaType));
        info.addContent(new Element("Publication_Name")
                .setText(AdmBsmeExportHelper.getMetdata(topStruct, config.getString("/metadata/titleLabel"))));
        info.addContent(
                new Element("Source_Organization").setText(sourceOrganisation));
        info.addContent(new Element("Barcode").setText(identifier));
        info.addContent(new Element("Event_Date").setText(eventDate));
        info.addContent(new Element("Event_Name").setText(eventName));
        info.addContent(new Element("Subject_ENG").setText(subjectEng));
        info.addContent(new Element("Subject_ARA").setText(subjectAra));
        info.addContent(new Element("Photographer").setText(photographer));
        info.addContent(new Element("Format").setText(format));
        info.addContent(new Element("Persons_in_Image").setText(personsInImage));
        info.addContent(new Element("location").setText(locations));
        info.addContent(new Element("Description").setText(description));

        // info.addContent(new Element("Editor_in_Chief").setText(editorInChief));
        // info.addContent(new Element("Media_Group").setText(mediaGroup));

        // add all journal entries as technical notes
        info.addContent(createTechnicalNotesElementFromRelevantJournalEntries(process));

        // add file information
        List<Reference> refs = topStruct.getAllToReferences("logical_physical");
        if (refs != null) {
            for (Reference ref : refs) {
                DocStruct page = ref.getTarget();
                String realFileName = page.getImageName();
                String realFileNameWithoutExtension = realFileName.substring(0, realFileName.indexOf("."));

                // get the new file name for the image and reuse if created previously
                String exportFileName = fileMap.get(realFileNameWithoutExtension);
                if (exportFileName == null) {
                    exportFileName = identifier;
                    fileMap.put(realFileNameWithoutExtension, exportFileName);
                }

                // add file element
                Element master = new Element("master");

                // add image information
                try {
                    File realFile = new File(process.getImagesOrigDirectory(false),
                            realFileNameWithoutExtension + ".tif");
                    try (ImageManager sourcemanager = new ImageManager(realFile.toURI())) {
                        ImageInterpreter si = sourcemanager.getMyInterpreter();

                        // MimeType
                        // master.setAttribute("Format", si.getFormatType().getFormat().getMimeType());
                        master.addContent(new Element("Format").setText(si.getFormatType().getFormat().getMimeType()));

                        // Unit for the resolution, always ppi
                        // master.setAttribute("ResolutionUnit", "PPI");
                        master.addContent(new Element("ResolutionUnit").setText("PPI"));

                        // Resolution
                        // master.setAttribute("Resolution", String.valueOf(si.getOriginalImageXResolution()));
                        master.addContent(new Element("Resolution").setText(String.valueOf(si.getOriginalImageXResolution())));

                        // ColorDepth
                        // master.setAttribute("BitDepth", String.valueOf(si.getColordepth()));
                        master.addContent(new Element("BitDepth").setText(String.valueOf(si.getColordepth())));

                        // bitonal, grey, "color"
                        // master.setAttribute("ColorSpace", si.getFormatType().getColortype().getLabel());
                        master.addContent(new Element("ColorSpace").setText(si.getFormatType().getColortype().getLabel()));

                        // Scanning device
                        master.addContent(new Element("ScanningDevice").setText(vr.replace("${process.Capturing device}")));

                        // Scanning device id
                        master.addContent(new Element("ScanningDeviceID"));

                        // Width
                        master.addContent(new Element("Width").setText(String.valueOf(si.getOriginalImageWidth())));

                        // Height
                        master.addContent(new Element("Height").setText(String.valueOf(si.getOriginalImageHeight())));
                        sourcemanager.close();
                    }
                } catch (IOException | SwapException | DAOException | ImageManagerException e) {
                    log.error("Error while reading image metadata", e);
                    return false;
                }

                master.addContent(new Element("file").setText(exportFileName + ".tif"));
                doc.getRootElement().addContent(master);
            }
        }

        // copy all important files to target folder
        try {
            AdmBsmeExportHelper.copyFolderContent(process.getImagesOrigDirectory(false), "tif", fileMap, targetFolder);
        } catch (IOException | SwapException | DAOException e) {
            log.error("Error while copying the image files to export folder", e);
            return false;
        }

        // write the xml file
        XMLOutputter xmlOutputter = new XMLOutputter();
        xmlOutputter.setFormat(Format.getPrettyFormat());
        File xmlfile = new File(targetFolder + identifier + ".xml");
        try (FileOutputStream fileOutputStream = new FileOutputStream(xmlfile)) {
            xmlOutputter.output(doc, fileOutputStream);
        } catch (IOException e) {
            log.error("Error writing the simple xml file", e);
            return false;
        }

        return true;
    }

}