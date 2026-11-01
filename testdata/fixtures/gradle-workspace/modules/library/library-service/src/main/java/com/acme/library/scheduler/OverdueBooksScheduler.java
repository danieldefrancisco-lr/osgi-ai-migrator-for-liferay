package com.acme.library.scheduler;

import com.acme.library.model.Book;
import com.liferay.mail.kernel.model.MailMessage;
import com.liferay.mail.kernel.service.MailServiceUtil;
import com.liferay.portal.kernel.messaging.BaseMessageListener;
import com.liferay.portal.kernel.messaging.Message;

import javax.mail.internet.InternetAddress;

public class OverdueBooksScheduler extends BaseMessageListener {

	@Override
	protected void doReceive(Message message) throws Exception {
		MailMessage mailMessage = new MailMessage(
			new InternetAddress("library@acme.com"),
			new InternetAddress("member@acme.com"), "Overdue", "Return it", false);

		MailServiceUtil.sendEmail(mailMessage);
	}

}
