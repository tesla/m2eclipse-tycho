/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.sonatype.tycho.m2e.internal;

import static org.sonatype.tycho.m2e.internal.AbstractMavenBundlePluginProjectConfigurator.MOJO_ARTIFACT_ID;
import static org.sonatype.tycho.m2e.internal.AbstractMavenBundlePluginProjectConfigurator.MOJO_GROUP_ID;
import static org.sonatype.tycho.m2e.internal.AbstractMavenBundlePluginProjectConfigurator.isOsgiBundleProject;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.MavenProjectUtils;
import org.eclipse.m2e.core.project.configurator.ProjectConfigurationRequest;
import org.eclipse.m2e.jdt.IClasspathDescriptor;
import org.eclipse.m2e.jdt.IClasspathEntryDescriptor;
import org.eclipse.m2e.jdt.internal.AbstractJavaProjectConfigurator;
import org.eclipse.osgi.util.ManifestElement;
import org.eclipse.pde.internal.core.PDECore;
import org.osgi.framework.Constants;

@SuppressWarnings( "restriction" )
public class WrapperBundleProjectConfigurator
    extends AbstractJavaProjectConfigurator
{
    @Override
    protected void addMavenClasspathContainer( IClasspathDescriptor classpath )
    {
        // we don't need maven classpath container
    }

    @Override
    protected void addCustomClasspathEntries( IJavaProject javaProject, IClasspathDescriptor classpath )
        throws JavaModelException
    {
        super.addCustomClasspathEntries( javaProject, classpath );

        // add PDE classpath container
        if ( !classpath.containsPath( PDECore.REQUIRED_PLUGINS_CONTAINER_PATH ) )
        {
            classpath.addEntry( JavaCore.newContainerEntry( PDECore.REQUIRED_PLUGINS_CONTAINER_PATH ) );
        }
    }

    @Override
    protected void invokeJavaProjectConfigurators( IClasspathDescriptor classpath, ProjectConfigurationRequest request,
                                                   IProgressMonitor monitor )
        throws CoreException
    {
        addBundleClasspathEntries( classpath, request, monitor );

        super.invokeJavaProjectConfigurators( classpath, request, monitor );
    }

    @Override
    protected void addJavaNature( IProject project, IProgressMonitor monitor )
        throws CoreException
    {
        super.addJavaNature( project, monitor );

        PDEProjectHelper.addPDENature( project, monitor );
    }

    public void addBundleClasspathEntries( IClasspathDescriptor classpath, ProjectConfigurationRequest request,
                                           IProgressMonitor monitor )
        throws CoreException
    {
        IMavenProjectFacade facade = request.getMavenProjectFacade();
        IProject project = facade.getProject();

        if ( !isOsgiBundleProject( facade, monitor ) )
        {
            throw new IllegalArgumentException();
        }

        List<MojoExecution> executions = facade.getMojoExecutions( MOJO_GROUP_ID, MOJO_ARTIFACT_ID, monitor, "bundle" );

        if ( executions.size() != 1 )
        {
            throw new IllegalArgumentException();
        }

        File outputDirectory = getOutputDirectory( facade.getMavenProject() );

        // clean old junk
        // @TODO clean old linked resources
        IPath outputPath = MavenProjectUtils.getProjectRelativePath( project, outputDirectory.getAbsolutePath() );
        if ( outputPath != null )
        {
            IFolder folder = project.getFolder( outputPath );
            folder.delete( true, monitor );
        }

        // execute maven-bundle-plugin:bundle goal
        MojoExecution execution = amendExecution( executions.get( 0 ), facade );
        maven.execute( request.getMavenSession(), execution, monitor );

        // refresh generated resources
        project.getFolder( "META-INF" ).refreshLocal( IResource.DEPTH_INFINITE, monitor );
        if ( outputPath != null )
        {
            IFolder folder = project.getFolder( outputPath );
            folder.refreshLocal( IResource.DEPTH_INFINITE, monitor );
        }

        // create linked classpath entries
        try
        {
            addClasspathEntries( classpath, facade, monitor );
        }
        catch ( CoreException e )
        {
            throw e;
        }
        catch ( Exception e )
        {
            e.printStackTrace();
        }

    }

    protected void addClasspathEntries( IClasspathDescriptor classpath, IMavenProjectFacade facade,
                                        IProgressMonitor monitor )
        throws CoreException, Exception
    {
        IProject project = facade.getProject();
        MavenProject mavenProject = facade.getMavenProject( monitor );

        File outputDirectory = getOutputDirectory( mavenProject );

        Manifest mf;

        InputStream is = project.getFile( "META-INF/MANIFEST.MF" ).getContents();
        try
        {
            mf = new Manifest( is );
        }
        finally
        {
            IOUtil.close( is );
        }
        Attributes attrs = mf.getMainAttributes();
        String value = attrs.getValue( Constants.BUNDLE_CLASSPATH );

        for ( ManifestElement me : ManifestElement.parseHeader( Constants.BUNDLE_CLASSPATH, value ) )
        {
            String path = me.getValue();
            if ( !".".equals( path ) )
            {
                // create linked resource to make PDE launch configuration happy
                // otherwise it does not include our classpath entries to dev.properties file

                IFile file = project.getFile( path );
                file.createLink( Path.fromOSString( new File( outputDirectory, path ).getAbsolutePath() ),
                                 IResource.ALLOW_MISSING_LOCAL | IResource.REPLACE, monitor );

                IClasspathEntryDescriptor ed = classpath.addLibraryEntry( file.getFullPath() );
                ed.setExported( true );
            }
        }
    }

    protected MojoExecution amendExecution( MojoExecution original, IMavenProjectFacade facade )
    {
        MojoExecution execution =
            new MojoExecution( original.getPlugin(), "bundle", "tycho-m2e:" + original.getExecutionId() + ":bundle" );

        Xpp3Dom configuration = new Xpp3Dom( original.getConfiguration() );

        Xpp3Dom unpackBundle = configuration.getChild( "unpackBundle" );
        if ( unpackBundle == null )
        {
            unpackBundle = new Xpp3Dom( "unpackBundle" );
            configuration.addChild( unpackBundle );
        }
        unpackBundle.setValue( "true" );

        Xpp3Dom outputDirectory = configuration.getChild( "outputDirectory" );
        if ( outputDirectory == null )
        {
            outputDirectory = new Xpp3Dom( "outputDirectory" );
            configuration.addChild( outputDirectory );
        }
        outputDirectory.setValue( getOutputDirectory( facade.getMavenProject() ).getAbsolutePath() );

        execution.setConfiguration( configuration );
        execution.setMojoDescriptor( original.getMojoDescriptor() );
        execution.setLifecyclePhase( original.getLifecyclePhase() );

        return execution;
    }

    public static File getOutputDirectory( MavenProject project )
    {
        return new File( project.getBuild().getDirectory(), "m2e-tycho" ).getAbsoluteFile();
    }
}
