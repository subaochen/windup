package org.jboss.windup.rules.apps.tattletale;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.google.common.base.StandardSystemProperty;
import org.jboss.tattletale.Main;
import org.jboss.windup.config.AbstractRuleProvider;
import org.jboss.windup.config.GraphRewrite;
import org.jboss.windup.config.metadata.MetadataBuilder;
import org.jboss.windup.config.operation.GraphOperation;
import org.jboss.windup.graph.GraphContext;
import org.jboss.windup.graph.model.ProjectModel;
import org.jboss.windup.graph.model.WindupConfigurationModel;
import org.jboss.windup.graph.model.resource.FileModel;
import org.jboss.windup.graph.service.WindupConfigurationService;
import org.jboss.windup.reporting.model.ApplicationReportModel;
import org.jboss.windup.reporting.model.TemplateType;
import org.jboss.windup.reporting.service.ApplicationReportService;
import org.jboss.windup.reporting.service.ReportService;
import org.jboss.windup.util.exception.WindupException;
import org.ocpsoft.rewrite.config.Configuration;
import org.ocpsoft.rewrite.config.ConfigurationBuilder;
import org.ocpsoft.rewrite.context.EvaluationContext;

/**
 * Runs Tattletale on the Windup's input.
 */
public class TattletaleRuleProvider extends AbstractRuleProvider
{
    public static final String REPORT_TEMPLATE = "/reports/templates/embedded.ftl";
    private static final String TATTLETALE_REPORT_SUBDIR = "tattletale";
    public static final String REPORT_DESCRIPTION = "This report contains the results of running Tattletale on the input application.";

    public TattletaleRuleProvider()
    {
        super(MetadataBuilder.forProvider(TattletaleRuleProvider.class));
    }

    @Override
    public Configuration getConfiguration(GraphContext context)
    {
        return ConfigurationBuilder.begin()
                    .addRule()
                    .perform(new TattletaleOperation());
    }

    private class TattletaleOperation extends GraphOperation
    {
        private static final String TTALE_CONFIG_FILE_NAME = "tattletale-config.properties";

        @Override
        public void perform(GraphRewrite event, EvaluationContext context)
        {
            Boolean generateReport = (Boolean) event.getGraphContext().getOptionMap().get(EnableTattletaleReportOption.NAME);
            if (generateReport == null || !generateReport)
                return;

            WindupConfigurationModel configuration = WindupConfigurationService.getConfigurationModel(event.getGraphContext());
            for (FileModel input : configuration.getInputPaths())
            {
                String inputPath = input.getFilePath();
                Path reportDirectory = new ReportService(event.getGraphContext()).getReportDirectory();

                String tattletaleRelativePath = TATTLETALE_REPORT_SUBDIR + File.separator + input.getFileName();
                Path tattletaleReportPath = reportDirectory.resolve(tattletaleRelativePath);

                for (int i = 1; Files.exists(tattletaleReportPath); i++)
                {
                    tattletaleRelativePath = TATTLETALE_REPORT_SUBDIR + File.separator + input.getFileName() + "." + i;
                    tattletaleReportPath = reportDirectory.resolve(tattletaleRelativePath);
                }
                String tattletaleDir = tattletaleReportPath.toString();

                Main main = new Main();
                main.setSource(inputPath);
                main.setDestination(tattletaleDir);

                try
                {
                    // The only way Tattletale accepts configuration is through a file.
                    new File(tattletaleDir).mkdirs();
                    File configPath = new File(tattletaleDir, TTALE_CONFIG_FILE_NAME);
                    try (PrintStream str = new PrintStream(configPath))
                    {
                        str.append("enableDot=false\n"); // Whether to generate .dot and .png
                        str.append("graphvizDot=dot\n"); // Dot executable
                        str.close();
                    }
                    main.setConfiguration(configPath.getAbsolutePath());

                    /*
                     * HACK - reset temp directory temporarily as otherwise there are cases where tattletale
                     * will actually overwrite the input file. This can happen in cases where the application
                     * being analyzed is actually in the temp directory, due to a bug in Tattletale. (2016/05/19)
                     */
                    String previousTmpDir = StandardSystemProperty.JAVA_IO_TMPDIR.value();
                    System.setProperty(StandardSystemProperty.JAVA_IO_TMPDIR.key(), Paths.get(tattletaleDir).resolve("tattletaletmp").toString());
                    main.execute();
                    System.setProperty(StandardSystemProperty.JAVA_IO_TMPDIR.key(), previousTmpDir);

                    createReportModel(event.getGraphContext(), input, tattletaleRelativePath);
                }
                catch (Exception e)
                {
                    throw new WindupException("Failed to run Tattletale due to: " + e.getMessage());
                }
            }
        }

        private void createReportModel(GraphContext context, FileModel input, String reportRelativePath)
        {
            ProjectModel inputProjectModel = input.getProjectModel();

            ApplicationReportModel applicationReportModel = new ApplicationReportService(context).create();
            applicationReportModel.setReportName("Tattletale");
            applicationReportModel.setDescription(REPORT_DESCRIPTION);
            applicationReportModel.setReportIconClass("glyphicon tattletale-nav-logo");
            applicationReportModel.setReportPriority(500);

            applicationReportModel.setDisplayInApplicationReportIndex(true);
            applicationReportModel.setProjectModel(inputProjectModel);
            applicationReportModel.setTemplatePath(REPORT_TEMPLATE);
            applicationReportModel.setTemplateType(TemplateType.FREEMARKER);

            Map<String, String> reportProperties = new HashMap<>();
            reportProperties.put("embeddedTitle", "Tattletale Report");
            reportProperties.put("embeddedUrl", reportRelativePath + "/index.html");

            applicationReportModel.setReportProperties(reportProperties);

            ReportService reportService = new ReportService(context);
            reportService.setUniqueFilename(applicationReportModel, "tattletale" + "_" + inputProjectModel.getName(), "html");
        }
    }
}
