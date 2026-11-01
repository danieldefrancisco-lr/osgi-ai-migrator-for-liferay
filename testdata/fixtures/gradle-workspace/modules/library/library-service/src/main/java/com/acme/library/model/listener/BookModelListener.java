package com.acme.library.model.listener;

import com.acme.library.model.Book;
import com.acme.library.model.BookModel;
import com.liferay.portal.kernel.model.BaseModelListener;
import com.liferay.portal.kernel.model.ModelListener;

import org.osgi.service.component.annotations.Component;

@Component(service = ModelListener.class)
public class BookModelListener extends BaseModelListener<Book> {

	@Override
	public void onAfterCreate(Book book) {
		System.out.println("Created book " + book.getTitle());
	}

}
