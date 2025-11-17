@echo off
setlocal EnableDelayedExpansion

:: ==========================================
:: Automated Experiment Script for Figure 2
:: Runs 6 Protocols x 5 Densities = 30 Runs
:: ==========================================

:: Define the Total Node Densities (Paper: 50, 100, 150, 200, 250)
set DENSITIES=50 100 150 200 250

:: ------------------------------------------
:: 1. Standard Protocols
:: ------------------------------------------

for %%n in (%DENSITIES%) do (
    call :run_sim SprayAndWaitRouter %%n
)

for %%n in (%DENSITIES%) do (
    call :run_sim EncounterBasedRouter %%n
)

for %%n in (%DENSITIES%) do (
    call :run_sim DBRPRouter %%n
)

:: ------------------------------------------
:: 2. EMRT Protocols (Proposed)
:: ------------------------------------------

for %%n in (%DENSITIES%) do (
    call :run_sim_emrt SprayAndWaitEMRTRouter %%n SprayAndWaitEMRT 8
)

for %%n in (%DENSITIES%) do (
    call :run_sim_emrt EBREMRTRouter %%n EBREMRT 11
)

for %%n in (%DENSITIES%) do (
    call :run_sim_emrt DBRPEMRTRouter %%n DBRPEMRT 4
)

echo.
echo ==========================================
echo All simulations completed!
echo Check the 'reports/' folder.
echo ==========================================
pause
goto :eof


:: ------------------------------------------
:: Helper Functions
:: ------------------------------------------

:run_sim
set ROUTER=%1
set TOTAL_NODES=%2
:: 3 groups (p, c, w). No Trams.
set /A PER_GROUP=%TOTAL_NODES% / 3
set /A REAL_TOTAL=%PER_GROUP% * 3
set /A MAX_HOST_ID=%REAL_TOTAL% - 1

:: Changed | to - to prevent batch error
echo [Experiment] %ROUTER% - Nodes: ~%REAL_TOTAL% (Grp: %PER_GROUP%)
copy /Y default_settings.txt current_run.txt > nul
echo. >> current_run.txt
echo # --- Experiment Overrides --- >> current_run.txt
echo Group.router = %ROUTER% >> current_run.txt
echo Group1.nrofHosts = %PER_GROUP% >> current_run.txt
echo Group2.nrofHosts = %PER_GROUP% >> current_run.txt
echo Group3.nrofHosts = %PER_GROUP% >> current_run.txt
echo Events1.hosts = 0,%MAX_HOST_ID% >> current_run.txt
echo Scenario.name = %ROUTER%_%TOTAL_NODES% >> current_run.txt
if "%ROUTER%"=="SprayAndWaitRouter" (
    echo SprayAndWaitRouter.nrofCopies = 8 >> current_run.txt
)
call one.bat current_run.txt
goto :eof

:run_sim_emrt
set ROUTER=%1
set TOTAL_NODES=%2
set NS=%3
set MINIT=%4
set /A PER_GROUP=%TOTAL_NODES% / 3
set /A REAL_TOTAL=%PER_GROUP% * 3
set /A MAX_HOST_ID=%REAL_TOTAL% - 1

:: Changed | to - to prevent batch error
echo [Experiment] %ROUTER% - Nodes: ~%REAL_TOTAL% (Grp: %PER_GROUP%)
copy /Y default_settings.txt current_run.txt > nul
echo. >> current_run.txt
echo # --- Experiment Overrides --- >> current_run.txt
echo Group.router = %ROUTER% >> current_run.txt
echo Group1.nrofHosts = %PER_GROUP% >> current_run.txt
echo Group2.nrofHosts = %PER_GROUP% >> current_run.txt
echo Group3.nrofHosts = %PER_GROUP% >> current_run.txt
echo Events1.hosts = 0,%MAX_HOST_ID% >> current_run.txt
echo Scenario.name = %ROUTER%_%TOTAL_NODES% >> current_run.txt
echo %NS%.m_init = %MINIT% >> current_run.txt
echo %NS%.alpha = 0.85 >> current_run.txt
echo %NS%.updateInterval = 30 >> current_run.txt
call one.bat current_run.txt
goto :eof