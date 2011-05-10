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
import static org.sonatype.tycho.m2e.internal.AbstractMavenBundlePluginProjectConfigurator.getManifestPath;
import static org.sonatype.tycho.m2e.internal.AbstractMavenBundlePluginProjectConfigurator.isOsgiBundleProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.util.IOUtil;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.m2e.core.internal.M2EUtils;
import org.eclipse.m2e.core.lifecyclemapping.model.IPluginExecutionMetadata;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.configurator.AbstractBuildParticipant;
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
    public void configure( ProjectConfigurationRequest request, IProgressMonitor monitor )
        throws CoreException
    {
        super.configure( request, monitor );

        IProject project = request.getProject();
        MavenSession session = request.getMavenSession();
        IMavenProjectFacade facade = request.getMavenProjectFacade();
        PDEProjectHelper.addPDENature( project, getManifestPath(facade, session, monitor), monitor );
    }

    public void addBundleClasspathEntries( IClasspathDescriptor classpath, ProjectConfigurationRequest request,
                                           IProgressMonitor monitor )
        throws CoreException
    {
        IMavenProjectFacade facade = request.getMavenProjectFacade();

        if ( !isOsgiBundleProject( facade, monitor ) )
        {
            throw new IllegalArgumentException();
        }

        List<MojoExecution> executions = facade.getMojoExecutions( MOJO_GROUP_ID, MOJO_ARTIFACT_ID, monitor, "bundle" );

        if ( executions.size() != 1 )
        {
            throw new IllegalArgumentException();
        }

        // @TODO clean old copied resources

        // execute maven-bundle-plugin:bundle goal
        MojoExecution execution = executions.get( 0 ); // amendExecution( executions.get( 0 ), facade );
        MavenSession mavenSession = request.getMavenSession();

        maven.execute( mavenSession, execution, monitor );

        File bundleJar = mavenSession.getCurrentProject().getArtifact().getFile();

        // @TODO refresh bundleJar

        if ( bundleJar != null && bundleJar.canRead() )
        {
            // create linked classpath entries
            try
            {
                JarFile jf = new JarFile( bundleJar );

                try
                {
                    addClasspathEntries( classpath, facade, jf, monitor );
                }
                finally
                {
                    jf.close();
                }
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

    }

    protected IFile copyToWorkspace( JarFile jarFile, IProject project, String relPath, IProgressMonitor monitor )
        throws CoreException, IOException
    {
        ZipEntry ze = jarFile.getEntry( relPath );

        InputStream is = jarFile.getInputStream( ze );
        try
        {
            IFile ifile = project.getFile( relPath );
            
            IContainer parent = ifile.getParent();
            if ( parent instanceof IFolder )
            {
                M2EUtils.createFolder( (IFolder) parent, false, monitor );
            }

            if ( ifile.exists() )
            {
                ifile.setContents( is, true, false, monitor );
            }
            else
            {
                ifile.create( is, true, monitor );
                ifile.setDerived( true, monitor );
            }

            return ifile;
        }
        finally
        {
            IOUtil.close( is );
        }
    }

    protected void addClasspathEntries( IClasspathDescriptor classpath, IMavenProjectFacade facade, JarFile jf,
                                        IProgressMonitor monitor )
        throws CoreException, Exception
    {
        IProject project = facade.getProject();

        Manifest mf = jf.getManifest();

        Attributes attrs = mf.getMainAttributes();
        String value = attrs.getValue( Constants.BUNDLE_CLASSPATH );

        for ( ManifestElement me : ManifestElement.parseHeader( Constants.BUNDLE_CLASSPATH, value ) )
        {
            String path = me.getValue();
            if ( !".".equals( path ) )
            {
                // physically copy nested jars to make PDE launch configuration happy
                // otherwise it does not include our classpath entries to dev.properties file
                // or Bundle.getEntry does not work for the nested jars, if they are linked workspace resources

                IFile file = copyToWorkspace( jf, project, path, monitor );

                IClasspathEntryDescriptor ed = classpath.addLibraryEntry( file.getFullPath() );
                ed.setExported( true );
            }
        }
    }

    @Override
    public AbstractBuildParticipant getBuildParticipant( IMavenProjectFacade projectFacade, MojoExecution execution,
                                                         IPluginExecutionMetadata executionMetadata )
    {
        return AbstractMavenBundlePluginProjectConfigurator.getBuildParticipant( execution );
    }

}
