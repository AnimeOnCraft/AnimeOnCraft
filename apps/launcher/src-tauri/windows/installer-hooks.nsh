; Shared install/uninstall guard. Never force shutdown a launcher or other file holder.
!macro AOCWaitForUpdaterParent
  ClearErrors
  ${GetOptions} $CMDLINE "/AOC_PARENT_PID=" $R5
  ${IfNot} ${Errors}
    IntFmt $R6 "%u" $R5
    ${If} $R5 != $R6
    ${OrIf} $R5 == "0"
      SetErrorLevel 12
      Abort "Invalid updater parent. Installation was not changed."
    ${EndIf}
    System::Call 'kernel32::GetCurrentProcessId() i .r6'
    ${If} $R5 == $6
      SetErrorLevel 12
      Abort "Invalid updater parent. Installation was not changed."
    ${EndIf}
    System::Call 'kernel32::OpenProcess(i 0x00100000, i 0, i R5) p .r6 ?e'
    Pop $7
    ${If} $6 == 0
      ${If} $7 != 87
        SetErrorLevel 12
        Abort "Unable to wait for the launcher. Installation was not changed."
      ${EndIf}
    ${Else}
      System::Call 'kernel32::WaitForSingleObject(p r6, i 15000) i .r7'
      System::Call 'kernel32::CloseHandle(p r6)'
      ${If} $7 != 0
        SetErrorLevel 12
        Abort "The launcher has not exited. Close it and retry. No process was stopped."
      ${EndIf}
    ${EndIf}
  ${EndIf}
  ClearErrors
!macroend
!ifmacrodef CheckIfAppIsRunning
  !macroundef CheckIfAppIsRunning
!else
  !error "Unsupported Tauri NSIS template: running-application macro missing"
!endif

!macro CheckIfAppIsRunning executablePath productName
  !insertmacro RestartManager_StartSession $R0
  ${If} $R0 == ""
    SetErrorLevel 10
    Abort "Unable to check running applications. Close the launcher and retry."
  ${EndIf}
  !insertmacro RestartManager_RegisterFile $R0 "${executablePath}"
  ${If} $0 != 0
    !insertmacro RestartManager_EndSession $R0
    SetErrorLevel 10
    Abort "Unable to check the launcher. Close it and retry."
  ${EndIf}
  System::Call 'RSTRTMGR::RmGetList(p R0, *i .r1, *i .r2, p 0, *i .r3) i .r0'
  StrCpy $R1 $0
  !insertmacro RestartManager_EndSession $R0
  ${If} $R1 != 0
    SetErrorLevel 10
    Abort "Close ${productName} before installing or uninstalling. No running application was stopped."
  ${EndIf}
!macroend
