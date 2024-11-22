package de.intranda.goobi.plugins;

import de.intranda.goobi.plugins.exporters.GenericExporter;
import de.intranda.goobi.plugins.exporters.MagazineExporter;
import de.intranda.goobi.plugins.exporters.NegativeExporter;
import de.intranda.goobi.plugins.exporters.NewspaperExporter;
import de.intranda.goobi.plugins.exporters.PositiveExporter;
import de.intranda.goobi.plugins.exporters.SlideExporter;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.export.dms.ExportDms;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import org.goobi.beans.Process;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
        boolean success = true;

        // read mets file
        try {
            Prefs prefs = process.getRegelsatz().getPreferences();
            Fileformat ff = null;
            ff = process.readMetadataFile();
            DigitalDocument dd = ff.getDigitalDocument();
            DocStruct topStruct = dd.getLogicalDocStruct();

            switch (topStruct.getType().getName()) {
                case "Newspaper" -> {
                    NewspaperExporter ne = new NewspaperExporter(ConfigPlugins.getPluginConfig(title), process, prefs, dd);
                    success = ne.startExport();
                }
                case "Periodical" -> {
                    // do a regular export
                    IExportPlugin export = new ExportDms();
                    export.setExportFulltext(false);
                    export.setExportImages(false);
                    success = export.startExport(process);

                    // do the specific export
                    if (success) {
                        MagazineExporter ex = new MagazineExporter(ConfigPlugins.getPluginConfig(title), process, prefs, dd);
                        success = ex.startExport();
                    }
                }
                case "AdmNegative" -> {
                    NegativeExporter ex = new NegativeExporter(ConfigPlugins.getPluginConfig(title), process, prefs, dd);
                    success = ex.startExport();
                }
                case "AdmPositiveEnvelope" -> {
                    PositiveExporter ex = new PositiveExporter(ConfigPlugins.getPluginConfig(title), process, prefs, dd);
                    success = ex.startExport();
                }
                case "AdmSlide" -> {
                    SlideExporter ex = new SlideExporter(ConfigPlugins.getPluginConfig(title), process, prefs, dd);
                    success = ex.startExport();
                }
                case "AdmGeneric" -> {
                    GenericExporter ex = new GenericExporter(ConfigPlugins.getPluginConfig(title), process, prefs, dd);
                    success = ex.startExport();
                }
            }
        } catch (ReadException | PreferencesException | IOException |
                 SwapException e) {
            problems.add("Export aborted for process with ID: " + e.getMessage());
            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "Export aborte because of an unexpected exception: " + e.getMessage());
            log.error("Export aborted for process with ID " + process.getId(), e);
            return false;
        }

        if (!success) {
            log.error("Export aborted for process with ID " + process.getId());
        } else {
            log.info("Export executed for process with ID " + process.getId());
        }

        return success;
    }
}