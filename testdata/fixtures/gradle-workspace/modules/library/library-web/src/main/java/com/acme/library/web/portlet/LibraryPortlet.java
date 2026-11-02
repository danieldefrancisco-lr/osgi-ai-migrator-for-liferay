package com.acme.library.web.portlet;

import com.acme.library.model.Author;
import com.acme.library.model.Book;
import com.acme.library.model.BookWrapper;
import com.acme.library.model.Loan;
import com.liferay.journal.model.JournalArticle;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.portlet.bridges.mvc.MVCPortlet;

import javax.portlet.Portlet;

import org.osgi.service.component.annotations.Component;

@Component(
	property = {
		"com.liferay.portlet.display-category=category.sample",
		"javax.portlet.display-name=Library",
		"javax.portlet.init-param.view-template=/view.jsp"
	},
	service = Portlet.class
)
public class LibraryPortlet extends MVCPortlet {
}
