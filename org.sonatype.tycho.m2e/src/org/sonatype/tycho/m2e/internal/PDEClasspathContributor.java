/*******************************************************************************
 * Copyright (c) 2014 Takari, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.sonatype.tycho.m2e.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.ArtifactKey;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IMavenProjectRegistry;
import org.eclipse.m2e.jdt.IClasspathEntryDescriptor;
import org.eclipse.m2e.jdt.IClasspathManager;
import org.eclipse.m2e.jdt.internal.ClasspathEntryDescriptor;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.ExportPackageDescription;
import org.eclipse.osgi.service.resolver.StateHelper;
import org.eclipse.pde.core.IClasspathContributor;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;

@SuppressWarnings( "restriction" )
public class PDEClasspathContributor
    implements IClasspathContributor
{

    private static final IMavenProjectRegistry mavenProjects = MavenPlugin.getMavenProjectRegistry();

    @Override
    public List<IClasspathEntry> getInitialEntries( BundleDescription bundle )
    {
        return Collections.emptyList();
    }

    @Override
    public List<IClasspathEntry> getEntriesForDependency( BundleDescription bundle, BundleDescription addedDependency )
    {
        IPluginModelBase model = PluginRegistry.findModel( addedDependency );

        IProject project = getProject( model );

        if ( project == null )
        {
            return Collections.emptyList();
        }

        IMavenProjectFacade facade = mavenProjects.getProject( project );

        if ( facade == null )
        {
            return Collections.emptyList();
        }

        IJavaProject javaProject = JavaCore.create( project );

        if ( javaProject == null )
        {
            return Collections.emptyList();
        }

        List<IAccessRule> accessRules = getAccessRules( bundle, addedDependency );

        Map<ArtifactKey, String> embeddedArtifacts = EmbeddedArtifacts.getEmbeddedArtifacts( project );

        List<IClasspathEntry> entries = new ArrayList<>();

        for ( Map.Entry<ArtifactKey, IClasspathEntryDescriptor> entry : getMavenClasspath( javaProject ).entrySet() )
        {
            if ( embeddedArtifacts.containsKey( entry.getKey() ) )
            {
                entries.add( newClasspathEntry( entry.getValue(), accessRules ) );
            }
        }

        return entries;
    }

    private List<IAccessRule> getAccessRules( BundleDescription bundle, BundleDescription addedDependency )
    {
        Map<BundleDescription, ArrayList<Rule>> map = retrieveVisiblePackagesFromState( bundle );
        if ( map != null )
        {
            return getAccessRules( map.get( addedDependency ) );
        }
        return null;
    }

    private IClasspathEntry newClasspathEntry( IClasspathEntryDescriptor prototype, Collection<IAccessRule> rules )
    {
        IClasspathEntryDescriptor entry = new ClasspathEntryDescriptor( prototype.getEntryKind(), prototype.getPath() );
        entry.setArtifactKey( prototype.getArtifactKey() );
        if ( rules != null )
        {
            for ( IAccessRule rule : rules )
            {
                entry.addAccessRule( rule );
            }
        }
        return entry.toClasspathEntry();
    }

    private Map<ArtifactKey, IClasspathEntryDescriptor> getMavenClasspath( IJavaProject javaProject )
    {
        try
        {
            IClasspathContainer container =
                JavaCore.getClasspathContainer( new Path( IClasspathManager.CONTAINER_ID ), javaProject );
            if ( container != null )
            {
                Map<ArtifactKey, IClasspathEntryDescriptor> result = new LinkedHashMap<>();
                for ( IClasspathEntry entry : container.getClasspathEntries() )
                {
                    ClasspathEntryDescriptor descriptor = new ClasspathEntryDescriptor( entry );
                    ArtifactKey key = descriptor.getArtifactKey();
                    if ( key != null )
                    {
                        result.put( key, descriptor );
                    }
                }
                return result;
            }
        }
        catch ( JavaModelException e )
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return Collections.emptyMap();
    }

    private IProject getProject( IPluginModelBase model )
    {
        IResource resource = model.getUnderlyingResource();
        if ( resource == null )
        {
            return null; // not a workspace plugin
        }
        return resource.getProject();
    }

    // adopted from org.eclipse.pde.internal.core.PDEClasspathContainer

    private static final IAccessRule EXCLUDE_ALL_RULE =
        JavaCore.newAccessRule( new Path( "**/*" ), IAccessRule.K_NON_ACCESSIBLE | IAccessRule.IGNORE_IF_BETTER ); //$NON-NLS-1$

    public class Rule
    {
        IPath path;

        boolean discouraged;

        public boolean equals( Object other )
        {
            if ( !( other instanceof Rule ) )
                return false;
            return discouraged == ( (Rule) other ).discouraged && path.equals( ( (Rule) other ).path );
        }

        public String toString()
        {
            return discouraged ? path.toString() + " [discouraged]" : path.toString(); //$NON-NLS-1$
        }
    }

    private Map<BundleDescription, ArrayList<Rule>> retrieveVisiblePackagesFromState( BundleDescription desc )
    {
        Map<BundleDescription, ArrayList<Rule>> visiblePackages = new HashMap<BundleDescription, ArrayList<Rule>>();
        StateHelper helper = Platform.getPlatformAdmin().getStateHelper();
        addVisiblePackagesFromState( helper, desc, visiblePackages );
        if ( desc.getHost() != null )
            addVisiblePackagesFromState( helper, (BundleDescription) desc.getHost().getSupplier(), visiblePackages );
        return visiblePackages;
    }

    private void addVisiblePackagesFromState( StateHelper helper, BundleDescription desc,
                                              Map<BundleDescription, ArrayList<Rule>> visiblePackages )
    {
        if ( desc == null )
            return;
        ExportPackageDescription[] exports = helper.getVisiblePackages( desc );
        for ( int i = 0; i < exports.length; i++ )
        {
            BundleDescription exporter = exports[i].getExporter();
            if ( exporter == null )
                continue;
            ArrayList<Rule> list = visiblePackages.get( exporter );
            if ( list == null )
            {
                list = new ArrayList<Rule>();
                visiblePackages.put( exporter, list );
            }
            Rule rule = getRule( helper, desc, exports[i] );
            if ( !list.contains( rule ) )
                list.add( rule );
        }
    }

    private Rule getRule( StateHelper helper, BundleDescription desc, ExportPackageDescription export )
    {
        Rule rule = new Rule();
        rule.discouraged = helper.getAccessCode( desc, export ) == StateHelper.ACCESS_DISCOURAGED;
        String name = export.getName();
        rule.path = ( name.equals( "." ) ) ? new Path( "*" ) : new Path( name.replaceAll( "\\.", "/" ) + "/*" );
        return rule;
    }

    protected static List<IAccessRule> getAccessRules( Collection<Rule> rules )
    {
        if ( rules == null )
        {
            return null;
        }
        List<IAccessRule> accessRules = new ArrayList<>();
        for ( Rule rule : rules )
        {
            accessRules.add( rule.discouraged ? getDiscouragedRule( rule.path ) : getAccessibleRule( rule.path ) );
        }
        accessRules.add( EXCLUDE_ALL_RULE );
        return accessRules;
    }

    private static synchronized IAccessRule getAccessibleRule( IPath path )
    {
        return JavaCore.newAccessRule( path, IAccessRule.K_ACCESSIBLE );
    }

    private static synchronized IAccessRule getDiscouragedRule( IPath path )
    {
        return JavaCore.newAccessRule( path, IAccessRule.K_DISCOURAGED );
    }

}
