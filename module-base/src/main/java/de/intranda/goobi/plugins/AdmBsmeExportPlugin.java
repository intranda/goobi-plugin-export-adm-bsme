package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.goobi.beans.Process;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.export.dms.ExportDms;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
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

	/**
	 * Main export function
	 */
	@Override
	public boolean startExport(Process process, String destination)
			throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException, WriteException,
			MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException,
			DAOException, TypeNotAllowedForParentException {
		problems = new ArrayList<>();

		// read mets file
		try {
			Prefs prefs = process.getRegelsatz().getPreferences();
			Fileformat ff = null;
			ff = process.readMetadataFile();
			DigitalDocument dd = ff.getDigitalDocument();
			DocStruct topStruct = dd.getLogicalDocStruct();

			// if it is a NewspaperVolume do the Newspaper-Export
			if (topStruct.getType().getName().equals("Newspaper")) {
				NewspaperExporter ne = new NewspaperExporter(ConfigPlugins.getPluginConfig(title), process, prefs, dd);
				ne.startExport();
			} else {
				// do a regular export here
				IExportPlugin export = new ExportDms();
				export.setExportFulltext(true);
				export.setExportImages(true);

				// execute the export and check the success
				boolean success = export.startExport(process);
				if (!success) {
					log.error("Export aborted for process with ID " + process.getId());
				} else {
					log.info("Export executed for process with ID " + process.getId());
				}

			}

		} catch (ReadException | PreferencesException | IOException | SwapException e) {
			problems.add("Cannot read metadata file.");
			log.error("Export aborted for process with ID " + process.getId(), e);
			return false;
		}

		return true;
	}

}