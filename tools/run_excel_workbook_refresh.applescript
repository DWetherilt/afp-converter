on run argv
	if (count of argv) < 1 then error "usage: run_excel_workbook_refresh.applescript <workbook>"

	set workbookPath to POSIX path of (item 1 of argv)
	set workbookAlias to POSIX file workbookPath
	set stepName to "start"

	try
		tell application "Microsoft Excel"
			set stepName to "open-workbook"
			open workbookAlias
			set stepName to "run-vba"
			run VB macro "WorkbookUpdater.RefreshProgressFromJson"
		end tell
	on error errMsg number errNum
		set fullMsg to "Excel automation failed at " & stepName & ": " & errMsg
		error fullMsg number errNum
	end try
end run
