@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      http://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  benchmark startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Add default JVM options here. You can also use JAVA_OPTS and BENCHMARK_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto init

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto init

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:init
@rem Get command-line arguments, handling Windows variants

if not "%OS%" == "Windows_NT" goto win9xME_args

:win9xME_args
@rem Slurp the command line arguments.
set CMD_LINE_ARGS=
set _SKIP=2

:win9xME_args_slurp
if "x%~1" == "x" goto execute

set CMD_LINE_ARGS=%*

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\client-benchmarks-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\transport-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\elasticsearch-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\elasticsearch-x-content-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\cassandra-all-3.11.6.1.jar;%APP_HOME%\lib\commons-math3-3.2.jar;%APP_HOME%\lib\reindex-client-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\elasticsearch-rest-client-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\client-benchmark-noop-api-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\transport-netty4-client-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\lang-mustache-client-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\percolator-client-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\thrift-server-0.3.7.jar;%APP_HOME%\lib\cassandra-thrift-3.11.6.1.jar;%APP_HOME%\lib\libthrift-0.9.2.jar;%APP_HOME%\lib\httpclient-4.5.2.jar;%APP_HOME%\lib\httpcore-4.4.5.jar;%APP_HOME%\lib\httpasyncclient-4.1.2.jar;%APP_HOME%\lib\httpcore-nio-4.4.5.jar;%APP_HOME%\lib\commons-codec-1.10.jar;%APP_HOME%\lib\commons-logging-1.2.jar;%APP_HOME%\lib\elasticsearch-cli-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\elasticsearch-ssl-config-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\elasticsearch-core-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\elasticsearch-secure-sm-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\lucene-core-7.7.2.jar;%APP_HOME%\lib\lucene-analyzers-common-7.7.2.jar;%APP_HOME%\lib\lucene-backward-codecs-7.7.2.jar;%APP_HOME%\lib\lucene-grouping-7.7.2.jar;%APP_HOME%\lib\lucene-highlighter-7.7.2.jar;%APP_HOME%\lib\lucene-join-7.7.2.jar;%APP_HOME%\lib\lucene-memory-7.7.2.jar;%APP_HOME%\lib\lucene-misc-7.7.2.jar;%APP_HOME%\lib\lucene-queries-7.7.2.jar;%APP_HOME%\lib\lucene-queryparser-7.7.2.jar;%APP_HOME%\lib\lucene-sandbox-7.7.2.jar;%APP_HOME%\lib\lucene-spatial-7.7.2.jar;%APP_HOME%\lib\lucene-spatial-extras-7.7.2.jar;%APP_HOME%\lib\lucene-spatial3d-7.7.2.jar;%APP_HOME%\lib\lucene-suggest-7.7.2.jar;%APP_HOME%\lib\hppc-0.7.1.jar;%APP_HOME%\lib\joda-time-2.10.1.jar;%APP_HOME%\lib\snakeyaml-1.17.jar;%APP_HOME%\lib\jackson-core-2.8.11.jar;%APP_HOME%\lib\jackson-dataformat-smile-2.8.11.jar;%APP_HOME%\lib\jackson-dataformat-yaml-2.8.11.jar;%APP_HOME%\lib\jackson-dataformat-cbor-2.8.11.jar;%APP_HOME%\lib\jackson-mapper-asl-1.9.13.jar;%APP_HOME%\lib\jackson-core-asl-1.9.13.jar;%APP_HOME%\lib\t-digest-3.2.jar;%APP_HOME%\lib\HdrHistogram-2.1.9.jar;%APP_HOME%\lib\spatial4j-0.7.jar;%APP_HOME%\lib\jts-core-1.15.0.jar;%APP_HOME%\lib\log4j-api-2.11.1.jar;%APP_HOME%\lib\log4j-core-2.11.1.jar;%APP_HOME%\lib\jna-4.5.1.jar;%APP_HOME%\lib\airline-0.6.jar;%APP_HOME%\lib\ohc-core-j8-0.4.4.jar;%APP_HOME%\lib\ohc-core-0.4.4.jar;%APP_HOME%\lib\guava-19.0.jar;%APP_HOME%\lib\antlr-3.5.2.jar;%APP_HOME%\lib\ST4-4.0.8.jar;%APP_HOME%\lib\antlr-runtime-3.5.2.jar;%APP_HOME%\lib\high-scale-lib-1.0.6.jar;%APP_HOME%\lib\jamm-0.3.0.jar;%APP_HOME%\lib\commons-lang3-3.4.jar;%APP_HOME%\lib\netty-all-4.1.32.Final.jar;%APP_HOME%\lib\slf4j-api-1.7.25.jar;%APP_HOME%\lib\log4j-to-slf4j-2.11.1.jar;%APP_HOME%\lib\logback-classic-1.1.8.jar;%APP_HOME%\lib\logback-core-1.1.8.jar;%APP_HOME%\lib\javassist-3.20.0-GA.jar;%APP_HOME%\lib\jopt-simple-5.0.2.jar;%APP_HOME%\lib\parent-join-client-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\rank-eval-client-6.8.4.5-SNAPSHOT.jar;%APP_HOME%\lib\compiler-0.9.3.jar;%APP_HOME%\lib\snappy-java-1.1.1.7.jar;%APP_HOME%\lib\lz4-1.3.0.jar;%APP_HOME%\lib\compress-lzf-0.8.4.jar;%APP_HOME%\lib\commons-cli-1.3.1.jar;%APP_HOME%\lib\concurrentlinkedhashmap-lru-1.4.jar;%APP_HOME%\lib\log4j-over-slf4j-1.7.7.jar;%APP_HOME%\lib\jcl-over-slf4j-1.7.7.jar;%APP_HOME%\lib\json-simple-1.1.jar;%APP_HOME%\lib\jbcrypt-0.3m.jar;%APP_HOME%\lib\metrics-jvm-3.1.5.jar;%APP_HOME%\lib\reporter-config3-3.0.3.jar;%APP_HOME%\lib\metrics-core-3.1.0.jar;%APP_HOME%\lib\stream-2.5.2.jar;%APP_HOME%\lib\sigar-1.6.4.jar;%APP_HOME%\lib\ecj-4.4.2.jar;%APP_HOME%\lib\caffeine-2.2.6.jar;%APP_HOME%\lib\jctools-core-1.2.1.jar;%APP_HOME%\lib\jmxremote_optional-repackaged-5.0.jar;%APP_HOME%\lib\javax.inject-1.jar;%APP_HOME%\lib\reporter-config-base-3.0.3.jar;%APP_HOME%\lib\hibernate-validator-4.3.0.Final.jar;%APP_HOME%\lib\disruptor-3.0.1.jar;%APP_HOME%\lib\fastutil-6.5.7.jar;%APP_HOME%\lib\jflex-1.6.0.jar;%APP_HOME%\lib\concurrent-trees-2.4.0.jar;%APP_HOME%\lib\validation-api-1.0.0.GA.jar;%APP_HOME%\lib\jboss-logging-3.1.0.CR2.jar;%APP_HOME%\lib\ant-1.7.0.jar;%APP_HOME%\lib\ant-launcher-1.7.0.jar

@rem Execute benchmark
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %BENCHMARK_OPTS%  -classpath "%CLASSPATH%" org.elasticsearch.client.benchmark.BenchmarkMain %CMD_LINE_ARGS%

:end
@rem End local scope for the variables with windows NT shell
if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
rem Set variable BENCHMARK_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
if  not "" == "%BENCHMARK_EXIT_CONSOLE%" exit 1
exit /b 1

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
