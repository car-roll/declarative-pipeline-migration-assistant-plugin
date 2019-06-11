package io.jenkins.plugins.todeclarative.converter;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.coravy.hudson.plugins.github.GithubProjectProperty;
import htmlpublisher.HtmlPublisher;
import htmlpublisher.HtmlPublisherTarget;
import hudson.model.BooleanParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.JDK;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.Slave;
import hudson.model.StringParameterDefinition;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.BuildTrigger;
import hudson.tasks.LogRotator;
import hudson.tasks.Mailer;
import hudson.tasks.Maven;
import hudson.tasks.Shell;
import hudson.tasks.test.AggregatedTestResultPublisher;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.todeclarative.converter.api.ConverterRequest;
import io.jenkins.plugins.todeclarative.converter.api.ConverterResult;
import io.jenkins.plugins.todeclarative.converter.freestyle.FreestyleToDeclarativeConverter;
import jenkins.model.BuildDiscarderProperty;
import jenkins.model.Jenkins;
import jenkins.mvn.GlobalSettingsProvider;
import jenkins.triggers.ReverseBuildTrigger;
import org.apache.commons.io.IOUtils;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;
import org.jenkinsci.plugins.configfiles.maven.GlobalMavenSettingsConfig;
import org.jenkinsci.plugins.credentialsbinding.impl.SecretBuildWrapper;
import org.jenkinsci.plugins.credentialsbinding.impl.UsernamePasswordMultiBinding;
import org.jenkinsci.plugins.pipeline.modeldefinition.ast.ModelASTPipelineDef;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.ExtractResourceSCM;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.ToolInstallations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FreestyleTest
{

    @Rule
    public JenkinsRule j  = new JenkinsRule();

    @Test
    public void freestyle_conversion() throws Exception {

        Slave slave = j.createOnlineSlave();
        slave.setLabelString( "FOO_AGENT" );

        String projectName = Long.toString( System.currentTimeMillis() );
        FreeStyleProject p = j.createFreeStyleProject( projectName );
        p.addProperty( new GithubProjectProperty( "https://github.com/olamy/foo") );

        { // git
            List<UserRemoteConfig> repoList = new ArrayList<>();
            repoList.add(new UserRemoteConfig("https://github.com/olamy/foo.git", null,
                                              "master", null));
            repoList.add(new UserRemoteConfig("https://github.com/olamy/bar.git", null,
                                              "patch-1", "credsId"));
            GitSCM gitSCM = new GitSCM( repoList, null, false,
                                        Collections.emptyList(), null, null, Collections.emptyList() );
            p.setScm( gitSCM );
        }

        {
            //int daysToKeep, int numToKeep, int artifactDaysToKeep, int artifactNumToKeep
            LogRotator logRotator = new LogRotator( 1, 2, 3, 4 );
            BuildDiscarderProperty buildDiscarderProperty = new BuildDiscarderProperty( logRotator );
            p.addProperty( buildDiscarderProperty );
        }

        {
            RequiredResourcesProperty requiredResourcesProperty =
                new RequiredResourcesProperty( "beer", null, null, "labelName", null );
            p.addProperty( requiredResourcesProperty );
        }

        {
            List<ParameterDefinition> parametersDefinitions = new ArrayList<>();
            parametersDefinitions.add( new StringParameterDefinition( "str", "defaultValue", "description str", true ) );
            // List<String> toGroovy needs to be fixed
            //parametersDefinitions.add( new ChoiceParameterDefinition( "choice", new String[]{"choice1","choice2"}, "description choice" ) );
            parametersDefinitions.add( new BooleanParameterDefinition( "nameboolean", true, "boolean description" ) );
            ParametersDefinitionProperty parametersDefinitionProperty =
                new ParametersDefinitionProperty( parametersDefinitions );
            p.addProperty( parametersDefinitionProperty );
        }
        {
            ReverseBuildTrigger reverseBuildTrigger = new ReverseBuildTrigger( "pipeline" );
            reverseBuildTrigger.setThreshold( Result.UNSTABLE );
            p.addTrigger( reverseBuildTrigger );

            p.addTrigger(new TimerTrigger( "45 9-16/2 * * 1-5" ));
            SCMTrigger scmTrigger = new SCMTrigger("45 9-16/2 * * 1-5");
            scmTrigger.setIgnorePostCommitHooks( true );
            p.addTrigger( scmTrigger );
        }

        p.getBuildersList().add( new Shell( "pwd" ) );
        p.getBuildersList().add( new Shell( "ls -lrt" ) );

        {
            String username = "bob";
            String password = "s3cr3t";
            UsernamePasswordCredentialsImpl c =
                new UsernamePasswordCredentialsImpl( CredentialsScope.GLOBAL, null, "sample", username, password );
            CredentialsProvider.lookupStores( j ).iterator().next().addCredentials( Domain.global(), c );

            UsernamePasswordMultiBinding usernamePasswordMultiBinding =
                new UsernamePasswordMultiBinding( "theuser", "thepassword", c.getId() );
            SecretBuildWrapper secretBuildWrapper =
                new SecretBuildWrapper( Arrays.asList( usernamePasswordMultiBinding ) );

            p.getBuildWrappersList().add( secretBuildWrapper );
        }

        {
            ArtifactArchiver artifactArchiver = new ArtifactArchiver( "**/target/**.jar" );
            artifactArchiver.setAllowEmptyArchive( true );
            artifactArchiver.setExcludes( "**pom**" );
            artifactArchiver.setCaseSensitive( true );
            artifactArchiver.setFingerprint( true );
            artifactArchiver.setOnlyIfSuccessful( true );
            p.getPublishersList().add( artifactArchiver );
        }
        p.getPublishersList().add( new AggregatedTestResultPublisher("foo", true) );

        {
            p.getPublishersList().add( new BuildTrigger( "foo,bar", Result.SUCCESS ) );
            p.getPublishersList().add( new BuildTrigger( "beer", Result.SUCCESS ) );

            j.createFreeStyleProject( "foo" );
            j.createFreeStyleProject( "bar" );
            j.createFreeStyleProject( "beer" );
        }

        {
            HtmlPublisherTarget htmlPublisherTarget = new HtmlPublisherTarget("reportName", "reportDir", "reportFiles", /*keepAll*/true,
            /*alwaysLinkToLastBuild*/true, /*allowMissing*/true);
            p.getPublishersList().add( new HtmlPublisher( Arrays.asList( htmlPublisherTarget ) ));
        }

        {
            p.getPublishersList().add( new Mailer( "foo@beer.com", //
                /* notifyEveryUnstableBuild */ true, //
                /* sendToIndividuals*/ true ));
        }

        FreestyleToDeclarativeConverter converter = Jenkins.get()
            .getExtensionList( FreestyleToDeclarativeConverter.class ).get( 0 );

        Assert.assertTrue( converter.canConvert( p ) );

        ConverterRequest request = new ConverterRequest().job( p ).createdProjectName( "foo-beer" );
        ConverterResult converterResult = new ConverterResult()
            .modelASTPipelineDef( new ModelASTPipelineDef(null));

        converter.convert( request, converterResult);
        String groovy = converterResult.getModelASTPipelineDef().toPrettyGroovy();

        System.out.println(groovy);

        Assert.assertTrue(groovy.contains("branch: 'master'"));
        Assert.assertTrue(groovy.contains("url: 'https://github.com/olamy/foo.git'"));

        Assert.assertTrue(groovy.contains("credentialsId: 'credsId'"));

        Assert.assertEquals( 3, ((WorkflowJob)converterResult.getJob()).getTriggers().size() );

        System.out.println( converterResult.getJob().getProperties() );

    }

    @Test
    public void freestyle_conversion_then_run() throws Exception {

        Slave slave = j.createOnlineSlave();
        slave.setLabelString( "FOO_AGENT" );

        String projectName = Long.toString( System.currentTimeMillis() );
        FreeStyleProject p = j.createFreeStyleProject( projectName );
        p.addProperty( new GithubProjectProperty( "http://github.com/beer/paleale") );

        //int daysToKeep, int numToKeep, int artifactDaysToKeep, int artifactNumToKeep
        LogRotator logRotator = new LogRotator(1, 2,3, 4);
        BuildDiscarderProperty buildDiscarderProperty = new BuildDiscarderProperty( logRotator );
        p.addProperty( buildDiscarderProperty );

        List<ParameterDefinition> parametersDefinitions = new ArrayList<>();
        parametersDefinitions.add( new StringParameterDefinition( "str", "defaultValue", "description str", true ) );
        // List<String> toGroovy needs to be fixed
        //parametersDefinitions.add( new ChoiceParameterDefinition( "choice", new String[]{"choice1","choice2"}, "description choice" ) );
        parametersDefinitions.add( new BooleanParameterDefinition("nameboolean", true, "boolean description") );
        ParametersDefinitionProperty parametersDefinitionProperty = new ParametersDefinitionProperty(parametersDefinitions);
        p.addProperty( parametersDefinitionProperty );


        p.getBuildersList().add( new Shell( "pwd" ) );
        p.getBuildersList().add( new Shell( "ls -lrt" ) );
        p.getBuildersList().add( new Shell( "echo $str" ) );

        {
            String username = "bob";
            String password = "s3cr3t";
            UsernamePasswordCredentialsImpl c =
                new UsernamePasswordCredentialsImpl( CredentialsScope.GLOBAL, null, "sample", username, password );
            CredentialsProvider.lookupStores( j ).iterator().next().addCredentials( Domain.global(), c );

            UsernamePasswordMultiBinding usernamePasswordMultiBinding =
                new UsernamePasswordMultiBinding( "theuser", "thepassword", c.getId() );
            SecretBuildWrapper secretBuildWrapper =
                new SecretBuildWrapper( Arrays.asList( usernamePasswordMultiBinding ) );

            p.getBuildWrappersList().add( secretBuildWrapper );
        }

        {
            HtmlPublisherTarget htmlPublisherTarget = new HtmlPublisherTarget("reportName", "reportDir", "reportFiles", /*keepAll*/true,
                /*alwaysLinkToLastBuild*/true, /*allowMissing*/true);
            p.getPublishersList().add( new HtmlPublisher( Arrays.asList( htmlPublisherTarget ) ));
        }

        j.createFreeStyleProject( "foo" );
        p.getPublishersList().add( new AggregatedTestResultPublisher("foo", true) );


        FreestyleToDeclarativeConverter converter = Jenkins.get()
            .getExtensionList( FreestyleToDeclarativeConverter.class ).get( 0 );

        Assert.assertTrue( converter.canConvert( p ) );

        ConverterRequest request = new ConverterRequest().job( p ).createdProjectName( "foo-beer" );
        ConverterResult converterResult = new ConverterResult()
            .modelASTPipelineDef( new ModelASTPipelineDef(null));

        converter.convert( request, converterResult);
        String groovy = converterResult.getModelASTPipelineDef().toPrettyGroovy();

        System.out.println( groovy );

        System.out.println( converterResult.getJob().getProperties() );

        WorkflowRun run =( (WorkflowJob)converterResult.getJob()).scheduleBuild2( 0).get();
        j.waitForCompletion( run );
        j.assertBuildStatus( Result.SUCCESS, run);

    }

    @Test
    public void freestyle_conversion_only_Jenkinsfile() throws Exception {

        Slave slave = j.createOnlineSlave();
        slave.setLabelString( "FOO_AGENT" );

        String projectName = Long.toString( System.currentTimeMillis() );
        FreeStyleProject p = j.createFreeStyleProject( projectName );
        p.addProperty( new GithubProjectProperty( "http://github.com/beer/paleale") );

        //int daysToKeep, int numToKeep, int artifactDaysToKeep, int artifactNumToKeep
        LogRotator logRotator = new LogRotator(1, 2,3, 4);
        BuildDiscarderProperty buildDiscarderProperty = new BuildDiscarderProperty( logRotator );
        p.addProperty( buildDiscarderProperty );

        List<ParameterDefinition> parametersDefinitions = new ArrayList<>();
        parametersDefinitions.add( new StringParameterDefinition( "str", "defaultValue", "description str", true ) );
        // List<String> toGroovy needs to be fixed
        //parametersDefinitions.add( new ChoiceParameterDefinition( "choice", new String[]{"choice1","choice2"}, "description choice" ) );
        parametersDefinitions.add( new BooleanParameterDefinition("nameboolean", true, "boolean description") );
        ParametersDefinitionProperty parametersDefinitionProperty = new ParametersDefinitionProperty(parametersDefinitions);
        p.addProperty( parametersDefinitionProperty );


        p.getBuildersList().add( new Shell( "pwd" ) );

        FreestyleToDeclarativeConverter converter = Jenkins.get()
            .getExtensionList( FreestyleToDeclarativeConverter.class ).get( 0 );

        Assert.assertTrue( converter.canConvert( p ) );

        ConverterRequest request = new ConverterRequest().job( p ).createProject( false );
        ConverterResult converterResult = new ConverterResult()
            .modelASTPipelineDef( new ModelASTPipelineDef(null));

        converter.convert( request, converterResult);
        String groovy = converterResult.getModelASTPipelineDef().toPrettyGroovy();

        System.out.println( groovy );


    }

    @Test
    public void freestyle_conversion_maven_build_then_run_project() throws Exception {

        Slave slave = j.createOnlineSlave();
        slave.setLabelString( "FOO_AGENT" );

        String projectName = Long.toString( System.currentTimeMillis() );
        FreeStyleProject p = j.createFreeStyleProject( projectName );
        p.addProperty( new GithubProjectProperty( "https://github.com/olamy/foo") );

        //int daysToKeep, int numToKeep, int artifactDaysToKeep, int artifactNumToKeep
        LogRotator logRotator = new LogRotator(1, 2,3, 4);
        BuildDiscarderProperty buildDiscarderProperty = new BuildDiscarderProperty( logRotator );
        p.addProperty( buildDiscarderProperty );

        List<ParameterDefinition> parametersDefinitions = new ArrayList<>();
        parametersDefinitions.add(new StringParameterDefinition( "str", "defaultValue", "description str", true ) );
        // List<String> toGroovy needs to be fixed
        //parametersDefinitions.add( new ChoiceParameterDefinition( "choice", new String[]{"choice1","choice2"}, "description choice" ) );
        parametersDefinitions.add( new BooleanParameterDefinition("nameboolean", true, "boolean description") );
        ParametersDefinitionProperty parametersDefinitionProperty = new ParametersDefinitionProperty(parametersDefinitions);
        p.addProperty( parametersDefinitionProperty );

        // should work everywhere.... at least I hope
        p.setJDK( new JDK( "thejdk", System.getenv( "JAVA_HOME" )));

        p.setScm(new ExtractResourceSCM(Thread.currentThread().getContextClassLoader().getResource( "maven3-project.zip" )));


        GlobalConfigFiles store = j.getInstance().getExtensionList(GlobalConfigFiles.class)
            .get( GlobalConfigFiles.class);

        String content = IOUtils.toString(Thread.currentThread().getContextClassLoader().getResource( "global-maven-settings.xml" ));
        GlobalMavenSettingsConfig globalMavenSettingsConfig =
            new GlobalMavenSettingsConfig( "global-maven-settings-id", "global-maven-settings-name", "comment", content );
        store.save(globalMavenSettingsConfig);

        Maven.MavenInstallation mavenInstallation = ToolInstallations.configureMaven35();

        Maven maven = new Maven( "clean verify",mavenInstallation.getName() /*maven name*/, "pom.xml", "" /*properties*/,
                                 null /*jvmOptions*/, false /*usePrivateRepository*/,
                                 null /*SettingsProvider settings*/,/*GlobalSettingsProvider*/null);

        //GlobalMavenSettingsConfig.GlobalMavenSettingsConfigProvider.
        p.getBuildersList().add(maven);


        FreestyleToDeclarativeConverter converter = Jenkins.get()
            .getExtensionList( FreestyleToDeclarativeConverter.class ).get( 0 );

        Assert.assertTrue( converter.canConvert( p ) );

        ConverterRequest request = new ConverterRequest().job( p ).createProject( true ).createdProjectName( "foo-blabla" );
        ConverterResult converterResult = new ConverterResult()
            .modelASTPipelineDef( new ModelASTPipelineDef(null));

        converter.convert( request, converterResult);
        String groovy = converterResult.getModelASTPipelineDef().toPrettyGroovy();

        System.out.println( groovy );

        WorkflowRun run =( (WorkflowJob)converterResult.getJob()).scheduleBuild2( 0).get();
        j.waitForCompletion( run );
        j.assertBuildStatus( Result.SUCCESS, run);

    }

}
