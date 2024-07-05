<!doctype html>
<html>
<head>
    <meta http-equiv="Content-Type" content="text/html; charset=utf-8">
    <style>
    table {
        border-collapse: collapse;
        border-spacing: 0;
    }

    td {
        padding: 10px 5px;
        border-style: solid;
        border-width: 1px;
        overflow: hidden;
        word-break: normal;
    }

    th {;
        padding: 10px 5px;
        border-style: solid;
        border-width: 1px;
        overflow: hidden;
        word-break: normal;
    }
    </style>
</head>

<body>
<g:set var="grailsApplication" bean="grailsApplication"/>

<h1>CleanUp Removed Objects (${dbEntries.size()})</h1>

<table>
    <thead>
    <tr>
        <th>#</th>
        <th>Name</th>
        <th>Before in DB</th>
        <th>After in DB</th>
        <th>Diff</th>
    </tr>
    </thead>
    <g:each in="${dbEntries}" var="obj" status="i">
        <tr>
            <td>
                ${i + 1}
            </td>
            <td>
                ${obj.key}
            </td>
            <td>
                ${obj.value.countBeforeRemovedInDB}
            </td>
            <td>
                ${obj.value.countAfterRemovedInDB}
            </td>
            <td>
                ${obj.value.countBeforeRemovedInDB - obj.value.countAfterRemovedInDB}
            </td>
        </tr>
    </g:each>
</table>

<br>
<br>

(This message was automatically generated by the we:kb system)
</body>
</html>
