pipeline {
    agent any
    stages {
        stage('Clean') {
            steps {
                bat 'mvn clean'
            }
        }
        stage('Compile') {
            steps {
                bat 'mvn compile'
            }
        }
        stage('Test') {
            steps {
                bat 'mvn test -Dmaven.test.failure.ignore=true'      
            }
        }
        stage('PMD') {
            steps {
                bat 'mvn pmd:pmd'    
            }
        }
        stage('JaCoCo') {
            steps {
                bat 'mvn jacoco:report'  
            }
        }
        stage('Javadoc') {
            steps {
                bat 'mvn javadoc:javadoc'
            }
        }
        stage('Site') {
            steps {
                bat 'mvn site'
            }
        }
        stage('Package') {
            steps {
                bat 'mvn package -DskipTests'
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '**/target/site/**/*.*', fingerprint: true
            archiveArtifacts artifacts: '**/target/**/*.jar', fingerprint: true
            archiveArtifacts artifacts: '**/target/**/*.war', fingerprint: true
            junit '**/target/surefire-reports/*.xml'
        }
    }
}