<%@ include file="/init.jsp" %>

<h2>Library</h2>

<liferay-ui:search-container total="<%= BookLocalServiceUtil.getBooksCount() %>">
	<liferay-ui:search-container-results results="<%= BookLocalServiceUtil.getBooks(searchContainer.getStart(), searchContainer.getEnd()) %>" />
	<liferay-ui:search-container-row className="com.acme.library.model.Book" modelVar="book">
		<liferay-ui:search-container-column-text name="title" property="title" />
	</liferay-ui:search-container-row>
	<liferay-ui:search-iterator />
</liferay-ui:search-container>
