/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.sonatype.tycho.m2e.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.maven.project.MavenProject;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.m2e.core.project.MavenProjectUtils;
import org.eclipse.m2e.core.project.configurator.AbstractCustomizableLifecycleMapping;
import org.eclipse.m2e.core.project.configurator.ILifecycleMapping;
import org.eclipse.m2e.core.project.configurator.ProjectConfigurationRequest;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.internal.core.ClasspathComputer;
import org.eclipse.pde.internal.core.IPluginModelListener;
import org.eclipse.pde.internal.core.PDECore;
import org.eclipse.pde.internal.core.PluginModelDelta;
import org.eclipse.pde.internal.core.natures.PDE;
import org.eclipse.pde.internal.core.util.CoreUtility;

@SuppressWarnings( "restriction" )
public abstract class AbstractTychoLifecycleMapping
    extends AbstractCustomizableLifecycleMapping
    implements ILifecycleMapping
{
    private static boolean isListeningForPluginModelChanges = false;

    private static final List<IProject> projectsForUpdateClasspath = new ArrayList<IProject>();

    private static final IPluginModelListener classpathUpdater = new IPluginModelListener()
    {
        public void modelsChanged( PluginModelDelta delta )
        {
            if ( projectsForUpdateClasspath.size() == 0 )
            {
                return;
            }

            synchronized ( projectsForUpdateClasspath )
            {
                Iterator<IProject> projectsIter = projectsForUpdateClasspath.iterator();
                while ( projectsIter.hasNext() )
                {
                    IProject project = projectsIter.next();
                    IPluginModelBase model = PluginRegistry.findModel( project );
                    if ( model == null )
                    {
                        continue;
                    }

                    UpdateClasspathWorkspaceJob job = new UpdateClasspathWorkspaceJob( project, model );
                    job.schedule();
                    projectsIter.remove();
                }
            }
        }
    };

    @Override
    public void configure( ProjectConfigurationRequest request, IProgressMonitor monitor )
        throws CoreException
    {
        super.configure( request, monitor );

        MavenProject mavenProject = request.getMavenProject();
        IProject project = request.getProject();

        String packaging = mavenProject.getPackaging();
        if ( "eclipse-plugin".equals( packaging ) || "eclipse-test-plugin".equals( packaging ) )
        {
            configurePDEBundleProject( project, mavenProject, monitor );
        }
        else if ( "eclipse-feature".equals( packaging ) )
        {
            // see org.eclipse.pde.internal.ui.wizards.feature.AbstractCreateFeatureOperation
            if ( !project.hasNature( PDE.FEATURE_NATURE ) )
            {
                CoreUtility.addNatureToProject( project, PDE.FEATURE_NATURE, monitor );
            }
        }
        else if ( "eclipse-update-site".equals( packaging ) )
        {
            // see org.eclipse.pde.internal.ui.wizards.site.NewSiteProjectCreationOperation
            if ( !project.hasNature( PDE.SITE_NATURE ) )
            {
                CoreUtility.addNatureToProject( project, PDE.SITE_NATURE, monitor );
            }
        }
    }

    private void configurePDEBundleProject( IProject project, MavenProject mavenProject, IProgressMonitor monitor )
        throws CoreException
    {
        // see org.eclipse.pde.internal.ui.wizards.plugin.NewProjectCreationOperation

        if ( !project.hasNature( PDE.PLUGIN_NATURE ) )
        {
            CoreUtility.addNatureToProject( project, PDE.PLUGIN_NATURE, null );
        }

        if ( !project.hasNature( JavaCore.NATURE_ID ) )
        {
            CoreUtility.addNatureToProject( project, JavaCore.NATURE_ID, null );
        }

        // PDE can't handle default JDT classpath
        IJavaProject javaProject = JavaCore.create( project );
        javaProject.setRawClasspath( new IClasspathEntry[0], true, monitor );
        javaProject.setOutputLocation( getOutputLocation( project, mavenProject, monitor ), monitor );

        // see org.eclipse.pde.internal.ui.wizards.tools.UpdateClasspathJob
        // PDE populates the model cache lazily from WorkspacePluginModelManager.visit() ResourceChangeListenter
        // That means the model may be available or not at this point in the lifecycle.
        // If it is, update its classpath right away.
        // If not add the project to the list to be updated later based on model change events.
        IPluginModelBase model = PluginRegistry.findModel( project );
        if ( model != null )
        {
            ClasspathComputer.setClasspath( project, model );
        }
        else
        {
            addProjectForUpdateClasspath( project );
        }
    }

    private static class UpdateClasspathWorkspaceJob
        extends WorkspaceJob
    {
        private final IProject project;

        private final IPluginModelBase model;

        public UpdateClasspathWorkspaceJob( IProject project, IPluginModelBase model )
        {
            super( "Updating classpath" );
            this.project = project;
            this.model = model;
        }

        @Override
        public IStatus runInWorkspace( IProgressMonitor monitor )
            throws CoreException
        {
            ClasspathComputer.setClasspath( project, model );
            return Status.OK_STATUS;
        }
    }

    private void addProjectForUpdateClasspath( IProject project )
    {
        synchronized ( projectsForUpdateClasspath )
        {
            projectsForUpdateClasspath.add( project );
            if ( !isListeningForPluginModelChanges )
            {
                PDECore.getDefault().getModelManager().addPluginModelListener( classpathUpdater );
                isListeningForPluginModelChanges = true;
            }
        }
    }

    private IPath getOutputLocation( IProject project, MavenProject mavenProject, IProgressMonitor monitor )
        throws CoreException
    {
        File outputDirectory = new File( mavenProject.getBuild().getOutputDirectory() );
        outputDirectory.mkdirs();
        IPath relPath =
            MavenProjectUtils.getProjectRelativePath( project, mavenProject.getBuild().getOutputDirectory() );
        IFolder folder = project.getFolder( relPath );
        folder.refreshLocal( IResource.DEPTH_INFINITE, monitor );
        return folder.getFullPath();
    }

}
