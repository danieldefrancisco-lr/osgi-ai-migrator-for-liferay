package com.acme.library.service.wrapper;

import com.acme.library.service.BookLocalServiceWrapper;
import com.liferay.portal.kernel.service.ServiceWrapper;

import org.osgi.service.component.annotations.Component;

@Component(service = ServiceWrapper.class)
public class CustomBookLocalServiceWrapper extends BookLocalServiceWrapper {

	public CustomBookLocalServiceWrapper() {
		super(null);
	}

}
