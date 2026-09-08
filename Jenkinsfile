pipeline {
    agent any

    options {
        skipDefaultCheckout(true)
        timestamps()
        ansiColor('xterm')
        disableConcurrentBuilds()
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
    }

    parameters {
        string(name: 'DOCKER_REGISTRY', defaultValue: 'docker.io', description: 'Registry hostname, for example docker.io or registry.example.com')
        string(name: 'DOCKER_NAMESPACE', defaultValue: 'replace-me', description: 'Lower-case registry namespace or organization')
        string(name: 'DOCKER_CREDENTIALS_ID', defaultValue: 'dockerhub-blog-system', description: 'Jenkins username/password credential ID used for docker login')
        booleanParam(name: 'PUSH_IMAGES', defaultValue: true, description: 'Push the application images after tests and image builds pass')
    }

    environment {
        MAVEN_OPTS = '-Dmaven.repo.local=.m2/repository -Dstyle.color=never'
        DOCKER_BUILDKIT = '1'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Toolchain') {
            steps {
                sh '''
                    set -eu
                    java -version
                    mvn -version
                    node --version
                    npm --version
                    docker version --format '{{.Server.Version}}'
                '''
            }
        }

        stage('Backend test and package') {
            steps {
                dir('blog-server') {
                    sh 'mvn -B -ntp clean test package'
                }
            }
        }

        stage('Frontend test and build') {
            parallel {
                stage('Public web') {
                    steps {
                        dir('blog-web') {
                            sh 'npm ci --no-audit --no-fund'
                            sh 'npm test'
                            sh 'npm run build'
                        }
                    }
                }
                stage('Admin web') {
                    steps {
                        dir('blog-admin') {
                            sh 'npm ci --legacy-peer-deps --no-audit --no-fund'
                            sh 'npm test'
                            sh 'npm run build'
                        }
                    }
                }
            }
        }

        stage('Prepare image metadata') {
            steps {
                script {
                    def revision = sh(returnStdout: true, script: 'git rev-parse --short=12 HEAD').trim()
                    def safeRegistry = params.DOCKER_REGISTRY.trim().replaceAll('/+$', '')
                    def safeNamespace = params.DOCKER_NAMESPACE.trim().replaceAll('^/+', '').replaceAll('/+$', '')

                    if (!safeRegistry || !safeNamespace) {
                        error('DOCKER_REGISTRY and DOCKER_NAMESPACE must not be empty')
                    }
                    if (!(safeNamespace ==~ /[a-z0-9._\/-]+/)) {
                        error('DOCKER_NAMESPACE must contain only lower-case letters, digits, dot, underscore, slash, or hyphen')
                    }

                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${revision}"
                    env.IMAGE_BACKEND = "${safeRegistry}/${safeNamespace}/blog-system-blog-server"
                    env.IMAGE_WEB = "${safeRegistry}/${safeNamespace}/blog-system-blog-web"
                    env.IMAGE_ADMIN = "${safeRegistry}/${safeNamespace}/blog-system-blog-admin"
                    env.PUSH_LATEST = ((env.BRANCH_NAME ?: env.GIT_BRANCH ?: '').replaceFirst(/^origin\//, '') in ['main', 'master']).toString()

                    echo "Building images with tag ${env.IMAGE_TAG}; latest=${env.PUSH_LATEST}"
                }
            }
        }

        stage('Build Docker images') {
            parallel {
                stage('Backend image') {
                    steps {
                        sh 'docker build --pull -t "$IMAGE_BACKEND:$IMAGE_TAG" blog-server'
                    }
                }
                stage('Public web image') {
                    steps {
                        sh 'docker build --pull -t "$IMAGE_WEB:$IMAGE_TAG" blog-web'
                    }
                }
                stage('Admin web image') {
                    steps {
                        sh 'docker build --pull -t "$IMAGE_ADMIN:$IMAGE_TAG" blog-admin'
                    }
                }
            }
        }

        stage('Push Docker images') {
            when {
                expression { return params.PUSH_IMAGES }
            }
            steps {
                withCredentials([usernamePassword(
                    credentialsId: params.DOCKER_CREDENTIALS_ID,
                    usernameVariable: 'DOCKER_USERNAME',
                    passwordVariable: 'DOCKER_PASSWORD'
                )]) {
                    sh '''
                        set -eu
                        set +x
                        printf '%s' "$DOCKER_PASSWORD" | docker login "$DOCKER_REGISTRY" --username "$DOCKER_USERNAME" --password-stdin
                        set -x

                        docker push "$IMAGE_BACKEND:$IMAGE_TAG"
                        docker push "$IMAGE_WEB:$IMAGE_TAG"
                        docker push "$IMAGE_ADMIN:$IMAGE_TAG"

                        if [ "$PUSH_LATEST" = "true" ]; then
                            docker tag "$IMAGE_BACKEND:$IMAGE_TAG" "$IMAGE_BACKEND:latest"
                            docker tag "$IMAGE_WEB:$IMAGE_TAG" "$IMAGE_WEB:latest"
                            docker tag "$IMAGE_ADMIN:$IMAGE_TAG" "$IMAGE_ADMIN:latest"
                            docker push "$IMAGE_BACKEND:latest"
                            docker push "$IMAGE_WEB:latest"
                            docker push "$IMAGE_ADMIN:latest"
                        fi

                        docker logout "$DOCKER_REGISTRY" >/dev/null 2>&1 || true
                    '''
                }
            }
        }

        stage('Archive artifacts') {
            steps {
                archiveArtifacts artifacts: 'blog-server/target/*.jar,blog-web/dist/**,blog-admin/dist/**', fingerprint: true
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml'
        }
        cleanup {
            deleteDir()
        }
    }
}
