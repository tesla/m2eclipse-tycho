/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.sonatype.tycho.m2e.felix.internal;

import java.io.File;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.Scanner;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.lifecyclemapping.model.IPluginExecutionMetadata;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.MavenProjectChangedEvent;
import org.eclipse.m2e.core.project.configurator.AbstractBuildParticipant;
import org.eclipse.m2e.core.project.configurator.AbstractProjectConfigurator;
import org.eclipse.m2e.core.project.configurator.ILifecycleMappingConfiguration;
import org.eclipse.m2e.core.project.configurator.MojoExecutionBuildParticipant;
import org.eclipse.m2e.core.project.configurator.MojoExecutionKey;
import org.eclipse.m2e.core.project.configurator.ProjectConfigurationRequest;
import org.eclipse.osgi.util.ManifestElement;
import org.osgi.framework.BundleException;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * Straightforward maven-bundle-plugin configurator.
 * <p>
 * For projects that use {@code manifest} goal, executes the goal and refreshes generated/regenerated resources in
 * workspace.
 * <p>
 * For projects that use {@code bundle} goal, executes {@code manifest} goal instead. The idea is to only generate
 * bundle manifest inside Eclipse workspace, never generate packaged bundle jar.
 */
public class MavenBundlePluginConfigurator
    extends AbstractProjectConfigurator
{
    private static final QualifiedName PROP_FORCE_GENERATE =
        new QualifiedName( MavenBundlePluginConfigurator.class.getName(), "forceGenerate" );

    private static final ArtifactVersion VERSION_2_3_6 = new DefaultArtifactVersion( "2.3.6" );

    @Override
    public void configure( ProjectConfigurationRequest request, IProgressMonitor monitor )
        throws CoreException
    {
    }

    @Override
    public AbstractBuildParticipant getBuildParticipant( IMavenProjectFacade projectFacade, MojoExecution execution,
                                                         IPluginExecutionMetadata executionMetadata )
    {
        execution = amendMojoExecution( execution );

        return new MojoExecutionBuildParticipant( execution, true )
        {
            @Override
            public Set<IProject> build( int kind, IProgressMonitor monitor )
                throws Exception
            {
                BuildContext buildContext = getBuildContext();
                IMavenProjectFacade facade = getMavenProjectFacade();
                IProject project = facade.getProject();
                IFile manifest = getManifestFile( facade, getMojoExecution(), monitor );

                // regenerate bundle manifest if any of the following is true
                // - full workspace build
                // - PROP_FORCE_GENERATE project session property is set (see the comment below)
                // - generated bundle manifest changed (why?)
                // - files under project build output folder changed
                // - any of included bnd files changed

                boolean generate = IncrementalProjectBuilder.FULL_BUILD == kind;

                // the property is set by OsgiBundleProjectConfigurator.mavenProjectChanged is a workaround for
                // m2e design limitation, which does not allow project configurators trigger resource deltas
                // visible to build participants. See comment in OsgiBundleProjectConfigurator.mavenProjectChanged
                generate =
                    generate || Boolean.parseBoolean( (String) project.getSessionProperty( PROP_FORCE_GENERATE ) );
                // reset FORCE flag so we don't regenerate forever
                project.setSessionProperty( PROP_FORCE_GENERATE, null );

                IResourceDelta delta = getDelta( project );

                generate = generate || isManifestChange( delta, manifest );

                generate = generate || isIncludeChange( buildContext, monitor );

                generate = generate || isBuildOutputChange( buildContext, facade.getMavenProject( monitor ) );

                if ( !generate )
                {
                    return null;
                }

                Set<IProject> projects = super.build( kind, monitor );
                manifest.refreshLocal( IResource.DEPTH_INFINITE, monitor ); // refresh parent?

                return projects;
            }

            private boolean isIncludeChange( BuildContext buildContext, IProgressMonitor monitor )
                throws CoreException
            {
                IMaven maven = MavenPlugin.getMaven();

                MavenProject mavenProject = getMavenProjectFacade().getMavenProject( monitor );

                @SuppressWarnings( "unchecked" )
                Map<String, String> instructions =
                    maven.getMojoParameterValue( mavenProject, getMojoExecution(), "instructions", Map.class, monitor );

                if ( instructions == null )
                {
                    return false;
                }

                String include = instructions.get( "_include" );
                if ( include == null )
                {
                    return false;
                }

                ManifestElement[] elements;
                try
                {
                    elements = ManifestElement.parseHeader( "_include", include );
                }
                catch ( BundleException e )
                {
                    // assume nothing changed because BND won't be able to parse it either
                    return false;
                }

                for ( ManifestElement element : elements )
                {
                    String path = element.getValueComponents()[0];
                    if ( path.startsWith( "-" ) || path.startsWith( "~" ) )
                    {
                        path = path.substring( 1 );
                    }

                    // this does not detect changes in outside ${project.basedir}

                    if ( buildContext.hasDelta( path ) )
                    {
                        return true;
                    }
                }

                return false;
            }

            private boolean isManifestChange( IResourceDelta delta, IFile manifest )
            {
                return !manifest.isAccessible()
                    || ( delta != null && delta.findMember( manifest.getProjectRelativePath() ) != null );
            }

            private boolean isBuildOutputChange( BuildContext buildContext, MavenProject mavenProject )
            {
                Scanner ds = buildContext.newScanner( new File( mavenProject.getBuild().getOutputDirectory() ) );
                ds.scan();
                String[] includedFiles = ds.getIncludedFiles();
                return includedFiles != null && includedFiles.length > 0;
            }

        };
    }

    protected static MojoExecution amendMojoExecution( MojoExecution execution )
    {
        if ( "bundle".equals( execution.getGoal() ) )
        {
            // do not generate complete bundle. this is both slow and can produce unexpected workspace changes
            // that will trigger unexpected/endless workspace build.
            // we rely on the fact that ManifestPlugin mojo extends BundlePlugin and does not introduce any
            // additional required parameters, so can run manifest goal in place of bundle goal.
            MojoDescriptor descriptor = execution.getMojoDescriptor().clone();
            descriptor.setGoal( "manifest" );
            descriptor.setImplementation( "org.apache.felix.bundleplugin.ManifestPlugin" );
            MojoExecution _execution =
                new MojoExecution( execution.getPlugin(), "manifest", "m2e-tycho:" + execution.getExecutionId()
                    + ":manifest" );
            Xpp3Dom configuration = execution.getConfiguration();
            // workaround for https://issues.apache.org/jira/browse/FELIX-3254
            if ( VERSION_2_3_6.compareTo( new DefaultArtifactVersion( execution.getVersion() ) ) <= 0 )
            {
                configuration = new Xpp3Dom( configuration );
                Xpp3Dom rebuildBundle = new Xpp3Dom( "rebuildBundle" );
                rebuildBundle.setValue( "true" );
                configuration.addChild( rebuildBundle );
            }
            _execution.setConfiguration( configuration );
            _execution.setMojoDescriptor( descriptor );
            _execution.setLifecyclePhase( execution.getLifecyclePhase() );
            execution = _execution;
        }
        return execution;
    }

    @Override
    public void mavenProjectChanged( MavenProjectChangedEvent event, IProgressMonitor monitor )
        throws CoreException
    {
        if ( MavenProjectChangedEvent.KIND_CHANGED == event.getKind()
            && MavenProjectChangedEvent.FLAG_DEPENDENCIES == event.getFlags() )
        {
            forceManifestRegeneration( event.getMavenProject().getProject(), monitor );
        }
    }

    protected IFile getManifestFile( IMavenProjectFacade facade, MojoExecution execution, IProgressMonitor monitor )
        throws CoreException
    {
        File manifestFile =
            getParameterValue( facade.getMavenProject(), "manifestLocation", File.class, execution, monitor );
        IPath projectPath = facade.getProjectRelativePath( manifestFile.getAbsolutePath() ).append( "MANIFEST.MF" );
        return facade.getProject().getFile( projectPath );
    }

    @Override
    public boolean hasConfigurationChanged( IMavenProjectFacade newFacade,
                                            ILifecycleMappingConfiguration oldProjectConfiguration,
                                            MojoExecutionKey key, IProgressMonitor monitor )
    {
        if ( super.hasConfigurationChanged( newFacade, oldProjectConfiguration, key, monitor ) )
        {
            try
            {
                if ( !equalsManifestLocation( newFacade.getMojoExecution( key, monitor ),
                                              oldProjectConfiguration.getMojoExecutionConfiguration( key ) ) )
                {
                    return true;
                }

                forceManifestRegeneration( newFacade.getProject(), monitor );
            }
            catch ( CoreException e )
            {
                return true;
            }
        }

        return false;
    }

    private boolean equalsManifestLocation( MojoExecution mojoExecution, Xpp3Dom oldConfiguration )
    {
        // for now, just compare xml configuration, but ideally, getMetainfPath should be used to determine new manifest
        // location and the result should be compared to the currently configured PDE manifest location.

        if ( mojoExecution == null )
        {
            return true;
        }

        Xpp3Dom configuration = mojoExecution.getConfiguration();

        Xpp3Dom metainf = getManifestLocation( configuration );
        Xpp3Dom oldMetainf = getManifestLocation( oldConfiguration );

        return metainf != null ? metainf.equals( oldMetainf ) : oldMetainf == null;
    }

    protected Xpp3Dom getManifestLocation( Xpp3Dom configuration )
    {
        return configuration != null ? configuration.getChild( "manifestLocation" ) : null;
    }

    protected void forceManifestRegeneration( IProject project, IProgressMonitor monitor )
        throws CoreException
    {
        // this is a less pretty way to force bundle manifest regeneration.
        // the property is checked and reset by the build participant
        project.setSessionProperty( PROP_FORCE_GENERATE, "true" );
    }

}
