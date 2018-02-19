/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.sonatype.tycho.m2e.felix.internal;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
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
    private static final IMaven maven = MavenPlugin.getMaven();

    private static final QualifiedName PROP_FORCE_GENERATE =
        new QualifiedName( MavenBundlePluginConfigurator.class.getName(), "forceGenerate" );

    private static final ArtifactVersion VERSION_2_3_6 = new DefaultArtifactVersion( "2.3.6" );

    public static final String PARAM_MANIFESTLOCATION = "manifestLocation";

    @Override
    public void configure( ProjectConfigurationRequest request, IProgressMonitor monitor )
        throws CoreException
    {
    }

    @Override
    public AbstractBuildParticipant getBuildParticipant( IMavenProjectFacade projectFacade,
                                                         final MojoExecution execution,
                                                         IPluginExecutionMetadata executionMetadata )
    {
        return new AbstractBuildParticipant()
        {
            @Override
            public Set<IProject> build( int kind, IProgressMonitor monitor )
                throws Exception
            {
                BuildContext buildContext = getBuildContext();
                IMavenProjectFacade facade = getMavenProjectFacade();
                IProject project = facade.getProject();
                MavenProject mavenProject = facade.getMavenProject( monitor );

                @SuppressWarnings( "unchecked" )
                Map<String, String> instructions =
                    maven.getMojoParameterValue( mavenProject, execution, "instructions", Map.class, monitor );

                MojoExecution _execution = amendMojoExecution( mavenProject, execution, instructions );

                IFile manifest = getManifestFile( facade, _execution, monitor );

                // regenerate bundle manifest if any of the following is true
                // - full workspace build
                // - PROP_FORCE_GENERATE project session property is set (see the comment below)
                // - any of included bnd files changed

                boolean generate = IncrementalProjectBuilder.FULL_BUILD == kind;

                // the property is set by OsgiBundleProjectConfigurator.mavenProjectChanged is a workaround for
                // m2e design limitation, which does not allow project configurators trigger resource deltas
                // visible to build participants. See comment in OsgiBundleProjectConfigurator.mavenProjectChanged
                generate =
                    generate || Boolean.parseBoolean( (String) project.getSessionProperty( PROP_FORCE_GENERATE ) );
                // reset FORCE flag so we don't regenerate forever
                project.setSessionProperty( PROP_FORCE_GENERATE, null );

                generate = generate || isIncludeBndFileChange( buildContext, instructions );

                if ( !generate )
                {
                    return null;
                }

                maven.execute( mavenProject, _execution, monitor );

                manifest.refreshLocal( IResource.DEPTH_INFINITE, monitor ); // refresh parent?

                if ( isDeclerativeServices( mavenProject.getBasedir(), instructions ) )
                {
                    IFolder outputFolder = getOutputFolder( monitor, facade, _execution );
                    outputFolder.getFolder( "OSGI-OPT" ).refreshLocal( IResource.DEPTH_INFINITE, monitor );
                    outputFolder.getFolder( "OSGI-INF" ).refreshLocal( IResource.DEPTH_INFINITE, monitor );
                }

                return null;
            }

            protected IFolder getOutputFolder( IProgressMonitor monitor, IMavenProjectFacade facade,
                                               MojoExecution _execution )
                throws CoreException
            {
                File outputDirectory =
                    getParameterValue( facade.getMavenProject(), "outputDirectory", File.class, _execution, monitor );
                IPath outputPath = facade.getProjectRelativePath( outputDirectory.getAbsolutePath() );
                IFolder outputFolder = facade.getProject().getFolder( outputPath );
                return outputFolder;
            }

            private boolean isIncludeBndFileChange( BuildContext buildContext, Map<String, String> instructions )
                throws CoreException
            {
                for ( String path : getIncludeBndFilePaths( instructions ) )
                {
                    // this does not detect changes in outside ${project.basedir}

                    if ( buildContext.hasDelta( path ) )
                    {
                        return true;
                    }
                }

                return false;
            }

            @Override
            public void clean( IProgressMonitor monitor )
                throws CoreException
            {
                IMavenProjectFacade facade = getMavenProjectFacade();
                MavenProject mavenProject = facade.getMavenProject( monitor );

                @SuppressWarnings( "unchecked" )
                Map<String, String> instructions =
                    maven.getMojoParameterValue( mavenProject, execution, "instructions", Map.class, monitor );

                if ( isDeclerativeServices( mavenProject.getBasedir(), instructions ) )
                {
                    IFolder outputFolder = getOutputFolder( monitor, facade, execution );
                    outputFolder.getFolder( "OSGI-OPT" ).delete( true, monitor );
                    outputFolder.getFolder( "OSGI-INF" ).delete( true, monitor );
                }
            }
        };
    }

    protected static MojoExecution amendMojoExecution( MavenProject mavenProject, MojoExecution execution,
                                                       Map<String, String> instructions )
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
            _execution.setConfiguration( execution.getConfiguration() );
            _execution.setMojoDescriptor( descriptor );
            _execution.setLifecyclePhase( execution.getLifecyclePhase() );
            execution = _execution;
        }

        Xpp3Dom configuration = new Xpp3Dom( execution.getConfiguration() );
        if ( VERSION_2_3_6.compareTo( new DefaultArtifactVersion( execution.getVersion() ) ) <= 0 )
        {
            setBoolean( configuration, "rebuildBundle", true );
        }

        if ( isDeclerativeServices( mavenProject.getBasedir(), instructions ) )
        {
            setBoolean( configuration, "unpackBundle", true );
        }

        execution.setConfiguration( configuration );

        return execution;
    }

    protected static boolean isDeclerativeServices( Map<String, String> instructions )
    {
        return instructions.containsKey( "Service-Component" ) || instructions.containsKey( "_dsannotations" );
    }

    protected static boolean isDeclerativeServices( File basedir, Map<String, String> instructions )
    {
        if ( isDeclerativeServices( instructions ) )
        {
            return true;
        }

        // Properties class can be used to read bnd files http://www.aqute.biz/Bnd/Format
        for ( String path : getIncludeBndFilePaths( instructions ) )
        {
            if ( isDeclerativeServices( loadBndFile( new File( basedir, path ) ) ) )
            {
                return true;
            }
        }

        return false;
    }

    private static Map<String, String> loadBndFile( File file )
    {
        Properties properties = new Properties();
        try (InputStream is = new BufferedInputStream( new FileInputStream( file ) ))
        {
            properties.load( is );
        }
        catch ( IOException e )
        {
            // TODO create error marker
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for ( String key : properties.stringPropertyNames() )
        {
            map.put( key, properties.getProperty( key ) );
        }
        return map;
    }

    static List<String> getIncludeBndFilePaths( Map<String, String> instructions )
    {
        if ( instructions == null )
        {
            return Collections.emptyList();
        }

        String include = instructions.get( "_include" );
        if ( include == null )
        {
            return Collections.emptyList();
        }

        ManifestElement[] elements;
        try
        {
            elements = ManifestElement.parseHeader( "_include", include );
        }
        catch ( BundleException e )
        {
            // assume no included bnd files
            return Collections.emptyList();
        }

        List<String> includes = new ArrayList<String>();
        for ( ManifestElement element : elements )
        {
            String path = element.getValueComponents()[0];
            if ( path.startsWith( "-" ) || path.startsWith( "~" ) )
            {
                path = path.substring( 1 );
            }

            includes.add( path );
        }

        return includes;
    }

    private static void setBoolean( Xpp3Dom configuration, String name, boolean value )
    {
        Xpp3Dom parameter = configuration.getChild( name );
        if ( parameter == null )
        {
            parameter = new Xpp3Dom( name );
            configuration.addChild( parameter );
        }
        parameter.setValue( Boolean.toString( value ) );
    }

    @Override
    public void mavenProjectChanged( MavenProjectChangedEvent event, IProgressMonitor monitor )
        throws CoreException
    {
        if ( MavenProjectChangedEvent.KIND_CHANGED == event.getKind()
            && MavenProjectChangedEvent.FLAG_DEPENDENCIES == event.getFlags() 
            || MavenProjectChangedEvent.KIND_ADDED == event.getKind())
        {
            forceManifestRegeneration( event.getMavenProject().getProject(), monitor );
        }
    }

    protected IFile getManifestFile( IMavenProjectFacade facade, MojoExecution execution, IProgressMonitor monitor )
        throws CoreException
    {
        File manifestFile =
            getParameterValue( facade.getMavenProject(), PARAM_MANIFESTLOCATION, File.class, execution, monitor );
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
        return configuration != null ? configuration.getChild( PARAM_MANIFESTLOCATION ) : null;
    }

    protected void forceManifestRegeneration( IProject project, IProgressMonitor monitor )
        throws CoreException
    {
        // this is a less pretty way to force bundle manifest regeneration.
        // the property is checked and reset by the build participant
        project.setSessionProperty( PROP_FORCE_GENERATE, "true" );
    }

}
