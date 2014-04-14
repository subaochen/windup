package org.jboss.windup.windride.ui;


import java.io.File;
import javax.inject.Inject;
import org.jboss.forge.addon.resource.DirectoryResource;
import org.jboss.forge.addon.ui.command.UICommand;
import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.context.UIValidationContext;
import org.jboss.forge.addon.ui.input.UIInput;
import org.jboss.forge.addon.ui.metadata.UICommandMetadata;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.addon.ui.util.Categories;
import org.jboss.forge.addon.ui.util.Metadata;
import org.jboss.loom.conf.Configuration;
import org.jboss.loom.conf.ConfigurationValidator;
import org.jboss.windup.windride.impl.WindRideService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *  UI for WindRide Forge addon.
 * 
 *  @author Ondrej Zizka, ozizka at redhat.com
 */
public class WindRideUI implements UICommand {
    private static final Logger log = LoggerFactory.getLogger( WindRideUI.class );


    @Inject private WindRideService windride;

    private Configuration conf;
    
    
    // --- UI elements ---

    /** Source server - e.g. directory of EAP 5. */
    @Inject  @WithAttributes(label = "Source server directory", required = true)
    private UIInput<DirectoryResource> srcServerUI;

    /** Target server, e.g. AS 7. Used to start the server through Arquillian. */
    @Inject  @WithAttributes(label = "Target server directory", required = true)
    private UIInput<DirectoryResource> destServerUI;

    /** Directory to store the reports to. */
    @Inject  @WithAttributes(label = "Directory to store the reports to", required = false)
    private UIInput<DirectoryResource> reportDirUI;



    // Give Forge the command metadata.
    @Override public UICommandMetadata getMetadata( UIContext uic ) {
        return Metadata.forCommand(getClass()).name("Run WindRide 1.x")
              .description("Run WindRide 1.x server configuration migrator")
              .category(Categories.create("Platform", "Migration", "Configuration"));
    }


    public boolean isEnabled( UIContext uic ) {
        return true;
    }


    @Override public void initializeUI(final UIBuilder builder) throws Exception {
        builder.add(srcServerUI).add(destServerUI).add( reportDirUI );
    }


    /**
     *  Validate user input, and along the way, create the config object which will be used in execute().
     */
    public void validate( UIValidationContext uivc ) {
        Configuration conf = new Configuration();
        
        conf.getGlobal().getSourceServerConf().setDir( srcServerUI.getValue().getContents() );
        conf.getGlobal().getSourceServerConf().setProfileName("all");
        
        conf.getGlobal().getTargetServerConf().setDir( srcServerUI.getValue().getContents() ); // target/as7copy
        conf.getGlobal().getTargetServerConf().setConfigPath("standalone/configuration/standalone.xml");
        
        // If the user didn't choose reports dir, generate them into <CWD>/WindRide-report-<timestamp> .
        String reportDir = null;
        if( this.reportDirUI.hasValue() )
            reportDir = this.reportDirUI.getValue().getContents();
        else
            reportDir = System.getProperty("user.dir") + File.separator + "WindRide-report-" + System.currentTimeMillis();
            
        conf.getGlobal().setReportDir( reportDir );
        
        ConfigurationValidator.validate( conf );
        this.conf = conf;
    }


    /**
     *  Performs a migration as per configuration provided by the user.
     */
    public Result execute( UIExecutionContext uiec ) {
        try {
            windride.doMigration( this.conf );
            String msg = "Server config migration report stored in: " + this.conf.getGlobal().getReportDir();
            return Results.success( msg );
        }
        catch( Exception e ) {
            String msg = "Error executing WindRide: " + e.getMessage();
            log.error( msg, e );
            return Results.fail( msg, e );
        }
    }
    

}// class
