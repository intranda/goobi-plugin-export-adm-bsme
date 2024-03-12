package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.jdom2.CDATA;
import org.jdom2.Comment;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.export.dms.ExportDms;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class AdmBsmeExportPlugin implements IExportPlugin, IPlugin {

	@Getter
	private String title = "intranda_export_adm_bsme";
	@Getter
	private PluginType type = PluginType.Export;
	private Process process;
	
	// folder where to export to
	private String targetFolder;
	// keep a list of all image files as they need to be renamed
	private Map<String, String> fileMap;
	private int fileCounter;

	
	@Getter
	private List<String> problems;

	@Override
	public void setExportFulltext(boolean arg0) {
	}

	@Override
	public void setExportImages(boolean arg0) {
	}

	@Override
	public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException,
			PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException,
			UghHelperException, ReadException, SwapException, DAOException, TypeNotAllowedForParentException {
		String benutzerHome = process.getProjekt().getDmsImportImagesPath();
		return startExport(process, benutzerHome);
	}

	@Override
	public boolean startExport(Process process, String destination)
			throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException, WriteException,
			MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException,
			DAOException, TypeNotAllowedForParentException {
		this.process = process;
		problems = new ArrayList<>();
		fileMap = new HashMap<String, String>();
		fileCounter = 1;
		// read configured values from config file
		targetFolder = ConfigPlugins.getPluginConfig(title).getString("targetDirectory",
				"/opt/digiverso/goobi/output/");
		log.debug("Export directory for AdmBsmeExportPlugin: " + targetFolder);

		// read mets file
		try {
			Prefs prefs = process.getRegelsatz().getPreferences();
			Fileformat ff = null;
			ff = process.readMetadataFile();
			DigitalDocument dd = ff.getDigitalDocument();
			VariableReplacer replacer = new VariableReplacer(dd, prefs, process, null);

			// in case it is an anchor file get the first childe
			DocStruct topStruct = dd.getLogicalDocStruct();
			if (topStruct.getType().isAnchor() && topStruct.getAllChildren() != null
					&& !topStruct.getAllChildren().isEmpty()) {
				topStruct = topStruct.getAllChildren().get(0);
			}

			// if it is a NewspaperVolume do the Newspaper-Export
			if (topStruct.getType().getName().equals("NewspaperVolume")) {
				newspaperExport(topStruct);
			}

		} catch (ReadException | PreferencesException | IOException | SwapException e) {
			problems.add("Cannot read metadata file.");
			log.error("Export aborted for process with ID " + process.getId(), e);
			return false;
		}

		// do a regular export here
//        IExportPlugin export = new ExportDms();
//        export.setExportFulltext(true);
//        export.setExportImages(true);

		// execute the export and check the success
//        boolean success = export.startExport(process);
//        if (!success) {
//            log.error("Export aborted for process with ID " + process.getId());
//        } else {
//            log.info("Export executed for process with ID " + process.getId());
//        }
		return true;
	}

	/**
	 * do the export explicitely for newspaper volumes
	 * 
	 * @param topStruct
	 */
	private void newspaperExport(DocStruct topStruct) {
		// run through all NewspaperIssues
		for (DocStruct ds : topStruct.getAllChildrenAsFlatList()) {
			if (ds.getType().getName().equals("NewspaperIssue")) {

				// prepare xml document
				Document doc = new Document();
				doc.setRootElement(new Element("newspaper"));

				// add volume information
				Element volume = new Element("volume");
				doc.getRootElement().addContent(volume);
				String volumeId = getMetdata(topStruct, "CatalogIDDigital");
				volume.addContent(new Element("Rights_to_Use").setText("Yes"));
				volume.addContent(new Element("Right_Details").setText("ADMN"));
				volume.addContent(new Element("Source").setText("Goobi"));
				volume.addContent(new Element("Media_type").setText("Publication"));
				volume.addContent(new Element("Publication_Name").setText(getMetdata(topStruct, "TitleDocMain")));
				volume.addContent(new Element("Language").setText(getLanguageFullname(topStruct, "DocLanguage")));
				volume.addContent(new Element("Source_Organization").setText("Abu Dhabi Media Company"));
				volume.addContent(new Element("Source_ID").setText(volumeId));
				volume.addContent(new Element("Technical_Notes").setText(getMetdata(topStruct, "TechnicalNotes")));

				// add issue information
				Element issue = new Element("issue");
				doc.getRootElement().addContent(issue);
				String simpleDate = getMetdata(ds, "DateIssued").replace("-", "");
				issue.addContent(new Element("Issue_Date").setText(simpleDate));
				issue.addContent(new Element("Issue_number").setText(getMetdata(ds, "CurrentNo")));
				issue.addContent(new Element("Issue_Frequency").setText("Daily"));
				issue.addContent(new Element("Open_In_Viewer")
						.setText("https://adm.goobi.cloud/viewer/" + volumeId + "_" + simpleDate));

				// add file information
				Element files = new Element("files");
				doc.getRootElement().addContent(files);
				List<Reference> refs = ds.getAllToReferences("logical_physical");
				if (refs != null) {
					for (Reference ref : refs) {
						DocStruct page = ref.getTarget();
                        String realFileName = page.getImageName();
                        realFileName = realFileName.substring(0, realFileName.indexOf("."));
                        
                        // get the new file name for the image and reuse if created previously
                        String exportFileName = fileMap.get(realFileName);
                        if (exportFileName == null) {
                        	String counter = String.format("%07d" , fileCounter++);
                        	exportFileName = volumeId + "-" + counter;
                        	fileMap.put(realFileName, exportFileName);
                        }
                        files.addContent(new Element("file").setText(exportFileName + ".tif"));
					}
				}
				
				// write the xml file
				XMLOutputter xmlOutputter = new XMLOutputter();
				xmlOutputter.setFormat(Format.getPrettyFormat());
				File xmlfile = new File(targetFolder + volumeId + "-" + simpleDate + "-simple.xml");
				try (FileOutputStream fileOutputStream = new FileOutputStream(xmlfile)) {
					xmlOutputter.output(doc, fileOutputStream);
				} catch (IOException e) {
					log.error("Error writing the simple xml file", e);
				}

			}
		}
		
		// copy all important files to target folder
		try {
			copyFolderContent(process.getImagesOrigDirectory(false), "tif");
			copyFolderContent(process.getOcrAltoDirectory(), "xml");
			copyFolderContent(process.getOcrTxtDirectory(), "txt");
		} catch (IOException | SwapException | DAOException e) {
			log.error("Error while copying the image files to export folder", e);
		}
	}

	
	/**
	 * copy files to target directory
	 * 
	 * @param ds
	 * @throws IOException 
	 */
	private void copyFolderContent(String sourcefolder, String ext) throws IOException {
		for (Path pathIn : StorageProvider.getInstance().listFiles(sourcefolder)) {
			String fileIn = pathIn.getFileName().toString();
			fileIn = fileIn.substring(0, fileIn.indexOf("."));
			String fileOut = fileMap.get(fileIn);
			Path pathOut = Paths.get(targetFolder, fileOut + "." + ext);
			log.debug(pathIn + " ---> " + pathOut );
			StorageProvider.getInstance().copyFile(pathIn, pathOut);
		}
	}
	
	/**
	 * get a specific metadata from given docstruct
	 * 
	 * @param ds
	 */
	private String getMetdata(DocStruct ds, String field) {
		// run through all metadata to find the right one
		for (Metadata md : ds.getAllMetadata()) {
			if (md.getType().getName().equals(field)) {
				return md.getValue();
			}
		}
		return "";
	}

	/**
	 * get the language from metadata
	 * 
	 * @param ds
	 */
	private String getLanguageFullname(DocStruct ds, String field) {
		String lang = getMetdata(ds, field);
		switch (lang) {
		case "ara":
			return "Arabic";
		case "ger":
			return "German";
		case "eng":
			return "English";
		}
		return lang;
	}

}