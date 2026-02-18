Attribute VB_Name = "WorkbookUpdater"
Option Explicit

Private Const SHEET_CURRENT As String = "Current Progress"
Private Const SHEET_HISTORY As String = "Status History"
Private Const SHEET_TREND As String = "Completion Trend"
Private Const SHEET_GRAPH As String = "Progress Graph"

Public Sub RefreshProgressFromJson()
    Dim jsonPath As String
    jsonPath = ActiveWorkbook.Path & Application.PathSeparator & ".." & Application.PathSeparator & "preview" & Application.PathSeparator & "project-plan-progress-data.json"

    RefreshProgressFromJsonForWorkbookPath ActiveWorkbook.FullName, jsonPath, False
End Sub

Public Sub RefreshProgressFromJsonForWorkbookPath(ByVal workbookPath As String, ByVal jsonPath As String, Optional ByVal interactive As Boolean = False)
    Dim targetWorkbook As Workbook
    Set targetWorkbook = ResolveTargetWorkbook(workbookPath)

    UpdateCurrentProgressFromJson targetWorkbook, jsonPath
    AppendStatusHistoryFromCurrent targetWorkbook
    RebuildCompletionTrendFromHistory targetWorkbook
    RebuildProgressGraphSheet targetWorkbook

    targetWorkbook.Save
    If interactive Then
        MsgBox "Progress workbook refreshed from JSON: " & jsonPath, vbInformation
    End If
End Sub

Private Function ResolveTargetWorkbook(ByVal workbookPath As String) As Workbook
    Dim wb As Workbook
    For Each wb In Application.Workbooks
        If StrComp(wb.FullName, workbookPath, vbTextCompare) = 0 Then
            Set ResolveTargetWorkbook = wb
            Exit Function
        End If
    Next wb

    If Len(Trim$(workbookPath)) > 0 Then
        Set ResolveTargetWorkbook = Application.Workbooks.Open(workbookPath)
    Else
        Set ResolveTargetWorkbook = ActiveWorkbook
    End If
End Function

Private Sub UpdateCurrentProgressFromJson(ByVal targetWorkbook As Workbook, ByVal jsonPath As String)
    Dim jsonText As String
    jsonText = ReadAllText(jsonPath)

    Dim tasks As Collection
    Set tasks = ParseTasks(jsonText)

    Dim ws As Worksheet
    Set ws = targetWorkbook.Worksheets(SHEET_CURRENT)

    Dim headers(1 To 7) As String
    headers(1) = "workstream"
    headers(2) = "task"
    headers(3) = "priority"
    headers(4) = "status"
    headers(5) = "percent_complete"
    headers(6) = "last_updated"
    headers(7) = "notes"

    Dim i As Long
    For i = 1 To 7
        ws.Cells(1, i).Value = headers(i)
    Next i

    Dim lastRow As Long
    lastRow = WorksheetLastRow(ws)
    If lastRow >= 2 Then
        ws.Range("A2:G" & CStr(lastRow)).ClearContents
    End If

    Dim rowIndex As Long
    rowIndex = 2
    Dim taskRow As Variant
    For Each taskRow In tasks
        ws.Cells(rowIndex, 1).Value = taskRow(1)
        ws.Cells(rowIndex, 2).Value = taskRow(2)
        ws.Cells(rowIndex, 3).Value = taskRow(3)
        ws.Cells(rowIndex, 4).Value = taskRow(4)
        ws.Cells(rowIndex, 5).Value = taskRow(5)
        ws.Cells(rowIndex, 6).Value = taskRow(6)
        ws.Cells(rowIndex, 7).Value = taskRow(7)
        rowIndex = rowIndex + 1
    Next taskRow

    EnsureFilter ws, "A1:G" & CStr(Application.Max(2, rowIndex - 1))
End Sub

Private Sub AppendStatusHistoryFromCurrent(ByVal targetWorkbook As Workbook)
    Dim wsCurrent As Worksheet
    Dim wsHistory As Worksheet
    Set wsCurrent = targetWorkbook.Worksheets(SHEET_CURRENT)
    Set wsHistory = targetWorkbook.Worksheets(SHEET_HISTORY)

    Dim headers(1 To 11) As String
    headers(1) = "updated_at"
    headers(2) = "workstream"
    headers(3) = "task"
    headers(4) = "previous_status"
    headers(5) = "new_status"
    headers(6) = "previous_priority"
    headers(7) = "new_priority"
    headers(8) = "previous_percent"
    headers(9) = "new_percent"
    headers(10) = "csv_last_updated"
    headers(11) = "notes"

    Dim i As Long
    For i = 1 To 11
        wsHistory.Cells(1, i).Value = headers(i)
    Next i

    Dim nowStamp As String
    nowStamp = Format(Now, "yyyy-mm-dd\THH:nn:ss")

    Dim currentLast As Long
    currentLast = WorksheetLastRow(wsCurrent)

    Dim row As Long
    For row = 2 To currentLast
        Dim workstream As String
        Dim task As String
        Dim status As String
        Dim priority As String
        Dim percent As String
        Dim csvLastUpdated As String
        Dim notes As String

        workstream = Trim$(CStr(wsCurrent.Cells(row, 1).Value))
        task = Trim$(CStr(wsCurrent.Cells(row, 2).Value))
        priority = Trim$(CStr(wsCurrent.Cells(row, 3).Value))
        status = Trim$(CStr(wsCurrent.Cells(row, 4).Value))
        percent = Trim$(CStr(wsCurrent.Cells(row, 5).Value))
        csvLastUpdated = Trim$(CStr(wsCurrent.Cells(row, 6).Value))
        notes = Trim$(CStr(wsCurrent.Cells(row, 7).Value))

        If workstream = "" Or task = "" Then GoTo ContinueRow

        Dim lastHistoryRow As Long
        lastHistoryRow = FindLastHistoryRow(wsHistory, workstream, task)

        Dim prevStatus As String
        Dim prevPriority As String
        Dim prevPercent As String
        Dim prevCsvLast As String
        Dim prevNotes As String

        If lastHistoryRow > 0 Then
            prevStatus = Trim$(CStr(wsHistory.Cells(lastHistoryRow, 5).Value))
            prevPriority = Trim$(CStr(wsHistory.Cells(lastHistoryRow, 7).Value))
            prevPercent = Trim$(CStr(wsHistory.Cells(lastHistoryRow, 9).Value))
            prevCsvLast = Trim$(CStr(wsHistory.Cells(lastHistoryRow, 10).Value))
            prevNotes = Trim$(CStr(wsHistory.Cells(lastHistoryRow, 11).Value))
        Else
            prevStatus = ""
            prevPriority = ""
            prevPercent = ""
            prevCsvLast = ""
            prevNotes = ""
        End If

        If lastHistoryRow = 0 Or prevStatus <> status Or prevPriority <> priority Or prevPercent <> percent Or prevCsvLast <> csvLastUpdated Or prevNotes <> notes Then
            Dim appendRow As Long
            appendRow = WorksheetLastRow(wsHistory) + 1
            wsHistory.Cells(appendRow, 1).Value = nowStamp
            wsHistory.Cells(appendRow, 2).Value = workstream
            wsHistory.Cells(appendRow, 3).Value = task
            wsHistory.Cells(appendRow, 4).Value = prevStatus
            wsHistory.Cells(appendRow, 5).Value = status
            wsHistory.Cells(appendRow, 6).Value = prevPriority
            wsHistory.Cells(appendRow, 7).Value = priority
            wsHistory.Cells(appendRow, 8).Value = prevPercent
            wsHistory.Cells(appendRow, 9).Value = percent
            wsHistory.Cells(appendRow, 10).Value = csvLastUpdated
            wsHistory.Cells(appendRow, 11).Value = notes
        End If
ContinueRow:
    Next row
End Sub

Private Sub RebuildCompletionTrendFromHistory(ByVal targetWorkbook As Workbook)
    Dim wsHistory As Worksheet
    Dim wsTrend As Worksheet
    Set wsHistory = targetWorkbook.Worksheets(SHEET_HISTORY)
    Set wsTrend = EnsureWorksheet(targetWorkbook, SHEET_TREND)

    Dim headers(1 To 10) As String
    headers(1) = "workstream"
    headers(2) = "task"
    headers(3) = "priority"
    headers(4) = "iteration"
    headers(5) = "updated_at"
    headers(6) = "percent_complete"
    headers(7) = "delta_percent"
    headers(8) = "trend_points"
    headers(9) = "trend_line_ascii"
    headers(10) = "trend_formula_graph"

    Dim i As Long
    For i = 1 To 10
        wsTrend.Cells(1, i).Value = headers(i)
    Next i

    Dim previousTrendLast As Long
    previousTrendLast = WorksheetLastRow(wsTrend)
    If previousTrendLast >= 2 Then
        wsTrend.Range("A2:J" & CStr(previousTrendLast)).ClearContents
    End If

    Dim historyLast As Long
    historyLast = WorksheetLastRow(wsHistory)
    If historyLast < 2 Then
        EnsureFilter wsTrend, "A1:J2"
        Exit Sub
    End If

    Dim iterationByTask As New Collection
    Dim previousPercentByTask As New Collection

    Dim outRow As Long
    outRow = 2

    Dim row As Long
    For row = 2 To historyLast
        Dim workstream As String
        Dim task As String
        Dim priority As String
        Dim percentText As String
        Dim updatedAt As String
        Dim taskKey As String
        Dim iteration As Long
        Dim delta As String

        workstream = Trim$(CStr(wsHistory.Cells(row, 2).Value))
        task = Trim$(CStr(wsHistory.Cells(row, 3).Value))
        priority = Trim$(CStr(wsHistory.Cells(row, 7).Value))
        percentText = Trim$(CStr(wsHistory.Cells(row, 9).Value))
        updatedAt = Trim$(CStr(wsHistory.Cells(row, 10).Value))
        If updatedAt = "" Then
            updatedAt = Trim$(CStr(wsHistory.Cells(row, 1).Value))
        End If

        If workstream = "" Or task = "" Then GoTo ContinueRow

        taskKey = workstream & "|" & task
        iteration = CollectionGetLong(iterationByTask, taskKey) + 1
        CollectionSetLong iterationByTask, taskKey, iteration

        If CollectionHasKey(previousPercentByTask, taskKey) Then
            delta = FormatNumberForCell(ParseDouble(percentText) - CollectionGetDouble(previousPercentByTask, taskKey))
        Else
            delta = ""
        End If
        CollectionSetDouble previousPercentByTask, taskKey, ParseDouble(percentText)

        wsTrend.Cells(outRow, 1).Value = workstream
        wsTrend.Cells(outRow, 2).Value = task
        wsTrend.Cells(outRow, 3).Value = priority
        wsTrend.Cells(outRow, 4).Value = CStr(iteration)
        wsTrend.Cells(outRow, 5).Value = updatedAt
        wsTrend.Cells(outRow, 6).Value = percentText
        wsTrend.Cells(outRow, 7).Value = delta
        wsTrend.Cells(outRow, 8).Value = percentText
        wsTrend.Cells(outRow, 9).Value = BuildAsciiTrend(percentText)
        wsTrend.Cells(outRow, 10).Formula = "=REPT(""|"",ROUND(F" & CStr(outRow) & "/5,0))"
        outRow = outRow + 1
ContinueRow:
    Next row

    EnsureFilter wsTrend, "A1:J" & CStr(Application.Max(2, outRow - 1))
End Sub

Private Sub RebuildProgressGraphSheet(ByVal targetWorkbook As Workbook)
    Dim wsTrend As Worksheet
    Dim wsGraph As Worksheet
    Set wsTrend = targetWorkbook.Worksheets(SHEET_TREND)
    Set wsGraph = EnsureWorksheet(targetWorkbook, SHEET_GRAPH)

    Dim trendLast As Long
    trendLast = WorksheetLastRow(wsTrend)

    wsGraph.Cells.ClearContents
    wsGraph.Cells.ClearFormats
    wsGraph.Cells(1, 1).Value = "iteration"

    Dim existingChart As ChartObject
    For Each existingChart In wsGraph.ChartObjects
        existingChart.Delete
    Next existingChart

    If trendLast < 2 Then
        wsGraph.Cells(2, 1).Value = "No trend data available yet."
        Exit Sub
    End If

    Dim taskNamesByKey As New Collection
    Dim taskOrder As New Collection
    Dim maxIteration As Long
    maxIteration = 0

    Dim row As Long
    For row = 2 To trendLast
        Dim workstream As String
        Dim task As String
        Dim taskKey As String
        Dim taskName As String
        Dim iterText As String
        Dim iterValue As Long

        workstream = Trim$(CStr(wsTrend.Cells(row, 1).Value))
        task = Trim$(CStr(wsTrend.Cells(row, 2).Value))
        If workstream = "" Or task = "" Then GoTo ContinueCollect

        taskKey = workstream & "|" & task
        taskName = workstream & " | " & task
        If Not CollectionHasKey(taskNamesByKey, taskKey) Then
            taskNamesByKey.Add taskName, taskKey
            taskOrder.Add taskKey
        End If

        iterText = Trim$(CStr(wsTrend.Cells(row, 4).Value))
        iterValue = CLng(ParseDouble(iterText))
        If iterValue > maxIteration Then maxIteration = iterValue
ContinueCollect:
    Next row

    If taskOrder.Count = 0 Or maxIteration <= 0 Then
        wsGraph.Cells(2, 1).Value = "No trend data available yet."
        Exit Sub
    End If

    Dim taskCount As Long
    taskCount = taskOrder.Count
    Dim col As Long
    For col = 1 To taskCount
        wsGraph.Cells(1, col + 1).Value = taskNamesByKey(CStr(taskOrder(col)))
    Next col

    Dim r As Long
    For r = 2 To maxIteration + 1
        wsGraph.Cells(r, 1).Value = r - 1
    Next r

    Dim trendRangeStart As Long
    trendRangeStart = 2
    Dim trendRangeEnd As Long
    trendRangeEnd = Application.Max(2, trendLast)

    For col = 1 To taskCount
        Dim key As String
        Dim keyParts() As String
        Dim critWorkstream As String
        Dim critTask As String
        key = CStr(taskOrder(col))
        keyParts = Split(key, "|", 2)
        If UBound(keyParts) < 1 Then GoTo ContinueTaskFormula
        critWorkstream = Replace(keyParts(0), """", """""")
        critTask = Replace(keyParts(1), """", """""")
        For r = 2 To maxIteration + 1
            wsGraph.Cells(r, col + 1).Formula = _
                "=IFERROR(LOOKUP(2,1/(('Completion Trend'!$A$" & CStr(trendRangeStart) & ":$A$" & CStr(trendRangeEnd) & "=""" & critWorkstream & """)*('Completion Trend'!$B$" & CStr(trendRangeStart) & ":$B$" & CStr(trendRangeEnd) & "=""" & critTask & """)*('Completion Trend'!$D$" & CStr(trendRangeStart) & ":$D$" & CStr(trendRangeEnd) & "=$A" & CStr(r) & ")),'Completion Trend'!$F$" & CStr(trendRangeStart) & ":$F$" & CStr(trendRangeEnd) & "),NA())"
        Next r
ContinueTaskFormula:
    Next col

    wsGraph.Range(wsGraph.Cells(2, 2), wsGraph.Cells(maxIteration + 1, taskCount + 1)).NumberFormat = "0.0"

    Dim chartObj As ChartObject
    Set chartObj = wsGraph.ChartObjects.Add(20, 40, 1040, 440)
    chartObj.Name = "ProgressLineChart"
    With chartObj.Chart
        .ChartType = xlLineMarkers
        .SetSourceData wsGraph.Range(wsGraph.Cells(1, 1), wsGraph.Cells(maxIteration + 1, taskCount + 1))
        .HasTitle = True
        .ChartTitle.Text = "Task Completion by Iteration"
        .HasLegend = True
        .Axes(xlCategory).HasTitle = True
        .Axes(xlCategory).AxisTitle.Text = "Iteration"
        .Axes(xlValue).HasTitle = True
        .Axes(xlValue).AxisTitle.Text = "Completion (%)"
        .Axes(xlValue).MinimumScale = 0
        .Axes(xlValue).MaximumScale = 100
    End With
End Sub

Private Function EnsureWorksheet(ByVal targetWorkbook As Workbook, ByVal sheetName As String) As Worksheet
    On Error Resume Next
    Set EnsureWorksheet = targetWorkbook.Worksheets(sheetName)
    On Error GoTo 0
    If EnsureWorksheet Is Nothing Then
        Set EnsureWorksheet = targetWorkbook.Worksheets.Add(After:=targetWorkbook.Worksheets(targetWorkbook.Worksheets.Count))
        EnsureWorksheet.Name = sheetName
    End If
End Function

Private Function CollectionHasKey(ByVal col As Collection, ByVal key As String) As Boolean
    On Error GoTo Missing
    Dim v As Variant
    v = col.Item(key)
    CollectionHasKey = True
    Exit Function
Missing:
    Err.Clear
    CollectionHasKey = False
End Function

Private Function CollectionGetLong(ByVal col As Collection, ByVal key As String) As Long
    If CollectionHasKey(col, key) Then
        CollectionGetLong = CLng(col.Item(key))
    Else
        CollectionGetLong = 0
    End If
End Function

Private Sub CollectionSetLong(ByRef col As Collection, ByVal key As String, ByVal value As Long)
    If CollectionHasKey(col, key) Then col.Remove key
    col.Add value, key
End Sub

Private Function CollectionGetDouble(ByVal col As Collection, ByVal key As String) As Double
    If CollectionHasKey(col, key) Then
        CollectionGetDouble = CDbl(col.Item(key))
    Else
        CollectionGetDouble = 0#
    End If
End Function

Private Sub CollectionSetDouble(ByRef col As Collection, ByVal key As String, ByVal value As Double)
    If CollectionHasKey(col, key) Then col.Remove key
    col.Add value, key
End Sub

Private Function FormatNumberForCell(ByVal value As Double) As String
    If value = Int(value) Then
        FormatNumberForCell = CStr(Int(value))
    Else
        FormatNumberForCell = Format$(value, "0.0")
    End If
End Function

Private Function BuildAsciiTrend(ByVal percent As String) As String
    Dim p As Double
    p = ParseDouble(percent)
    Dim count As Long
    count = CLng(Round(p / 10, 0))
    If count < 0 Then count = 0
    BuildAsciiTrend = String(count, "|")
End Function

Private Function ParseDouble(ByVal valueText As String) As Double
    On Error Resume Next
    ParseDouble = CDbl(Replace(valueText, "%", ""))
    If Err.Number <> 0 Then
        ParseDouble = 0#
        Err.Clear
    End If
    On Error GoTo 0
End Function

Private Function WorksheetLastRow(ByVal ws As Worksheet) As Long
    Dim lastCell As Range
    Set lastCell = ws.Cells.Find(What:="*", After:=ws.Cells(1, 1), LookAt:=xlPart, LookIn:=xlFormulas, SearchOrder:=xlByRows, SearchDirection:=xlPrevious, MatchCase:=False)
    If lastCell Is Nothing Then
        WorksheetLastRow = 1
    Else
        WorksheetLastRow = lastCell.Row
    End If
End Function

Private Sub EnsureFilter(ByVal ws As Worksheet, ByVal refAddress As String)
    On Error Resume Next
    If ws.AutoFilterMode Then
        ws.AutoFilterMode = False
    End If
    ws.Range(refAddress).AutoFilter
    On Error GoTo 0
End Sub

Private Function FindLastHistoryRow(ByVal ws As Worksheet, ByVal workstream As String, ByVal task As String) As Long
    Dim lastRow As Long
    lastRow = WorksheetLastRow(ws)
    Dim r As Long
    For r = lastRow To 2 Step -1
        If Trim$(CStr(ws.Cells(r, 2).Value)) = workstream And Trim$(CStr(ws.Cells(r, 3).Value)) = task Then
            FindLastHistoryRow = r
            Exit Function
        End If
    Next r
    FindLastHistoryRow = 0
End Function

Private Function ReadAllText(ByVal filePath As String) As String
    Dim fileNumber As Integer
    fileNumber = FreeFile
    Open filePath For Input As #fileNumber
    ReadAllText = Input$(LOF(fileNumber), fileNumber)
    Close #fileNumber
End Function

Private Function ParseTasks(ByVal jsonText As String) As Collection
    Dim tasks As New Collection
    Dim arrStart As Long
    arrStart = InStr(1, jsonText, """tasks"":", vbTextCompare)
    If arrStart = 0 Then
        Set ParseTasks = tasks
        Exit Function
    End If

    Dim openBracket As Long
    openBracket = InStr(arrStart, jsonText, "[")
    If openBracket = 0 Then
        Set ParseTasks = tasks
        Exit Function
    End If

    Dim depth As Long
    Dim inString As Boolean
    Dim escaped As Boolean
    Dim objStart As Long
    Dim i As Long

    depth = 0
    inString = False
    escaped = False
    objStart = 0

    For i = openBracket + 1 To Len(jsonText)
        Dim ch As String
        ch = Mid$(jsonText, i, 1)

        If escaped Then
            escaped = False
        ElseIf ch = "\" Then
            escaped = True
        ElseIf ch = """" Then
            inString = Not inString
        ElseIf Not inString Then
            If ch = "{" Then
                If depth = 0 Then objStart = i
                depth = depth + 1
            ElseIf ch = "}" Then
                depth = depth - 1
                If depth = 0 And objStart > 0 Then
                    Dim objText As String
                    objText = Mid$(jsonText, objStart, i - objStart + 1)
                    tasks.Add ParseTaskObject(objText)
                    objStart = 0
                End If
            ElseIf ch = "]" And depth = 0 Then
                Exit For
            End If
        End If
    Next i

    Set ParseTasks = tasks
End Function

Private Function ParseTaskObject(ByVal objText As String) As Variant
    Dim v(1 To 7) As String
    v(1) = ExtractJsonString(objText, "workstream")
    v(2) = ExtractJsonString(objText, "task")
    v(3) = ExtractJsonString(objText, "priority")
    v(4) = ExtractJsonString(objText, "status")
    v(5) = ExtractJsonString(objText, "percent_complete")
    v(6) = ExtractJsonString(objText, "last_updated")
    v(7) = ExtractJsonString(objText, "notes")
    ParseTaskObject = v
End Function

Private Function ExtractJsonString(ByVal objText As String, ByVal key As String) As String
    Dim token As String
    token = """" & key & """:"""
    Dim p As Long
    p = InStr(1, objText, token, vbTextCompare)
    If p = 0 Then
        ExtractJsonString = ""
        Exit Function
    End If

    Dim i As Long
    i = p + Len(token)
    Dim out As String
    out = ""
    Dim escaped As Boolean
    escaped = False

    Do While i <= Len(objText)
        Dim ch As String
        ch = Mid$(objText, i, 1)
        If escaped Then
            Select Case ch
                Case """": out = out & """"
                Case "\": out = out & "\"
                Case "/": out = out & "/"
                Case "b": out = out & Chr$(8)
                Case "f": out = out & Chr$(12)
                Case "n": out = out & vbLf
                Case "r": out = out & vbCr
                Case "t": out = out & vbTab
                Case Else: out = out & ch
            End Select
            escaped = False
        ElseIf ch = "\" Then
            escaped = True
        ElseIf ch = """" Then
            Exit Do
        Else
            out = out & ch
        End If
        i = i + 1
    Loop

    ExtractJsonString = out
End Function
