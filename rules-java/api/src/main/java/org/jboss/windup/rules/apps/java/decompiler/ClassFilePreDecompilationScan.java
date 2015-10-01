package org.jboss.windup.rules.apps.java.decompiler;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;
import org.apache.commons.lang.StringUtils;
import org.jboss.windup.config.GraphRewrite;
import org.jboss.windup.config.operation.iteration.AbstractIterationOperation;
import org.jboss.windup.graph.model.resource.FileModel;
import org.jboss.windup.graph.service.GraphService;
import org.jboss.windup.reporting.service.ClassificationService;
import org.jboss.windup.rules.apps.java.DependencyVisitor;
import org.jboss.windup.rules.apps.java.model.JavaClassFileModel;
import org.jboss.windup.rules.apps.java.model.JavaClassModel;
import org.jboss.windup.rules.apps.java.scan.ast.ignore.JavaClassIgnoreResolver;
import org.jboss.windup.rules.apps.java.service.JavaClassService;
import org.jboss.windup.rules.apps.java.service.WindupJavaConfigurationService;
import org.jboss.windup.util.ExecutionStatistics;
import org.jboss.windup.util.Logging;
import org.objectweb.asm.ClassReader;
import org.ocpsoft.rewrite.context.EvaluationContext;

/**
 * An operation doing a pre-scan of the .class file in order to check if it is possible to tell in advance if it is worth decompiling the class.
 *
 * @author <a href="mailto:jesse.sightler@gmail.com">Jesse Sightler</a>
 * @author <a href="mailto:mbriskar@gmail.com">Matej Briskar</a>
 */
public class ClassFilePreDecompilationScan extends AbstractIterationOperation<JavaClassFileModel>
{
    private static Logger LOG = Logging.get(ClassFilePreDecompilationScan.class);


    private void addClassFileMetadata(GraphRewrite event, EvaluationContext context, JavaClassFileModel javaClassFileModel)
    {
        try (FileInputStream fis = new FileInputStream(javaClassFileModel.getFilePath()))
        {
            final ClassParser parser = new ClassParser(fis, javaClassFileModel.getFilePath());
            final JavaClass bcelJavaClass = parser.parse();
            final String packageName = bcelJavaClass.getPackageName();

            final String qualifiedName = bcelJavaClass.getClassName();

            final JavaClassService javaClassService = new JavaClassService(event.getGraphContext());
            final JavaClassModel javaClassModel = javaClassService.create(qualifiedName);
            int majorVersion = bcelJavaClass.getMajor();
            int minorVersion = bcelJavaClass.getMinor();

            String simpleName = qualifiedName;
            if (packageName != null && !packageName.equals("") && simpleName != null)
            {
                simpleName = StringUtils.substringAfterLast(simpleName, ".");
            }

            javaClassFileModel.setMajorVersion(majorVersion);
            javaClassFileModel.setMinorVersion(minorVersion);
            javaClassFileModel.setPackageName(packageName);

            javaClassModel.setSimpleName(simpleName);
            javaClassModel.setPackageName(packageName);
            javaClassModel.setQualifiedName(qualifiedName);
            javaClassModel.setClassFile(javaClassFileModel);
            javaClassModel.setPublic(bcelJavaClass.isPublic());
            javaClassModel.setInterface(bcelJavaClass.isInterface());

            final String[] interfaceNames = bcelJavaClass.getInterfaceNames();
            if (interfaceNames != null)
            {
                for (final String interfaceName : interfaceNames)
                {
                    JavaClassModel interfaceModel = javaClassService.getOrCreatePhantom(interfaceName);
                    javaClassService.addInterface(javaClassModel, interfaceModel);
                }
            }

            String superclassName = bcelJavaClass.getSuperclassName();
            if (!bcelJavaClass.isInterface() && !StringUtils.isBlank(superclassName))
                javaClassModel.setExtends(javaClassService.getOrCreatePhantom(superclassName));

            javaClassFileModel.setJavaClass(javaClassModel);
        }
        catch (Exception e)
        {
            LOG.log(Level.WARNING,
                        "BCEL was unable to parse class file: " + javaClassFileModel.getFilePath() + " due to: " + e.getMessage(),
                        e);
            ClassificationService classificationService = new ClassificationService(event.getGraphContext());
            classificationService.attachClassification(context, javaClassFileModel, JavaClassFileModel.UNPARSEABLE_CLASS_CLASSIFICATION,
                        JavaClassFileModel.UNPARSEABLE_CLASS_DESCRIPTION);
            javaClassFileModel.setSkipDecompilation(true);
        }
    }

    @Override
    public void perform(GraphRewrite event, EvaluationContext context, JavaClassFileModel fileModel)
    {
        ExecutionStatistics.get().begin("ClassFilePreDecompilationScan.perform()");
        try
        {
            try (InputStream is = fileModel.asInputStream())
            {
                addClassFileMetadata(event, context, fileModel);
                WindupJavaConfigurationService configurationService = new WindupJavaConfigurationService(event.getGraphContext());
                if (!configurationService.shouldScanFile(fileModel.getFilePath()))
                {
                    fileModel.asVertex().setProperty(JavaClassFileModel.SKIP_DECOMPILATION, true);
                    return;
                }

                // keep inner classes (we may need them for decompilation purposes)
                if (fileModel.getFileName().contains("$"))
                    return;

                DependencyVisitor dependencyVisitor = new DependencyVisitor();
                ClassReader classReader = new ClassReader(is);

                classReader.accept(dependencyVisitor, 0);
                for (String typeReference : dependencyVisitor.classes)
                {
                    if (shouldIgnore(typeReference)) {
                        fileModel.asVertex().setProperty(JavaClassFileModel.SKIP_DECOMPILATION, true);
                    }
                }


            }
            catch (IOException e)
            {
                LOG.log(Level.WARNING,
                            "ASM was unable to parse class file: " + fileModel.getFilePath() + " due to: " + e.getMessage(),
                            e);
                ClassificationService classificationService = new ClassificationService(event.getGraphContext());
                classificationService.attachClassification(context, fileModel, JavaClassFileModel.UNPARSEABLE_CLASS_CLASSIFICATION,
                            JavaClassFileModel.UNPARSEABLE_CLASS_DESCRIPTION);
                return;
            }
        }
        finally
        {
            ExecutionStatistics.get().end("ClassFilePreDecompilationScan.perform()");
        }
    }

    /**
     * This method is called on every reference that is in the .class file.
     * @param typeReference
     * @return
     */
    private boolean shouldIgnore(String typeReference)
    {
        typeReference = typeReference.replace('/', '.').replace('\\', '.');
        return JavaClassIgnoreResolver.singletonInstance().matches(typeReference);
    }



}