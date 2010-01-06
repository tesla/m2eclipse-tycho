package org.sonatype.tycho.m2e.internal;

import org.apache.maven.plugin.MojoExecution;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.pde.internal.core.natures.PDE;
import org.maven.ide.eclipse.project.IMavenProjectFacade;
import org.maven.ide.eclipse.project.configurator.AbstractProjectConfigurator;
import org.maven.ide.eclipse.project.configurator.ProjectConfigurationRequest;

@SuppressWarnings( "restriction" )
public class OsgiBundleProjectConfigurator
    extends AbstractProjectConfigurator
{
    public static final String MOJO_GROUP_ID = "org.apache.felix";

    public static final String MOJO_ARTIFACT_ID = "maven-bundle-plugin";

    @Override
    public void configure( ProjectConfigurationRequest request, IProgressMonitor monitor )
        throws CoreException
    {
        if ( isOsgiBundleProject( request.getMavenProjectFacade(), monitor ) )
        {
            addNature( request.getProject(), PDE.PLUGIN_NATURE, monitor );
        }
    }

    private boolean isOsgiBundleProject( IMavenProjectFacade facade, IProgressMonitor monitor )
        throws CoreException
    {
        for ( MojoExecution execution : facade.getExecutionPlan( monitor ).getExecutions() )
        {
            if ( MOJO_GROUP_ID.equals( execution.getGroupId() ) && MOJO_ARTIFACT_ID.equals( execution.getArtifactId() ) )
            {
                return true;
            }
        }
        return false;
    }
}
