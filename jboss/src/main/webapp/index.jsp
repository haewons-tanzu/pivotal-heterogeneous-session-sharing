<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<html>
<head>
    <title>This is Test App for WAS.</title>
</head>
<%
    Integer count = (session.getAttribute("count") == null) ? 0 : Integer.parseInt((String) session.getAttribute("count"));

    count = new Integer(count.intValue() + 1);
    session.setAttribute("count", Integer.toString(count));
%>
<body topmargin="0" bottommargin="0" leftmargin="0" rightmargin="0" scroll="no" style="overflow: hidden;">
<center>
  <h1>
    This session Id <%=session.getId()%> is visited <%=count%> times. <br><br>
    Is this session new? <%=session.isNew()%><br><br>
    &nbsp;&nbsp;<a href="https://<%=request.getServerName()%>">Reload</a>
    &nbsp;&nbsp;<a href="/shutdown">Shutdown</a>
  </h1>
</center>
</body>
</html>
