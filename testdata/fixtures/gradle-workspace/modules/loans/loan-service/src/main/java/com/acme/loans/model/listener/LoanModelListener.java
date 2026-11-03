package com.acme.loans.model.listener;

import com.acme.loans.model.Loan;
import com.liferay.portal.kernel.exception.ModelListenerException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.BaseModelListener;
import com.liferay.portal.kernel.model.ModelListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.osgi.service.component.annotations.Component;

@Component(service = ModelListener.class)
public class LoanModelListener extends BaseModelListener<Loan> {

	/** When a book comes back, tell the inventory system so the copy is available again. */
	@Override
	public void onAfterUpdate(Loan originalLoan, Loan loan) throws ModelListenerException {
		if (!"returned".equals(loan.getStatus()) || "returned".equals(originalLoan.getStatus())) {
			return;
		}

		String json = "{\"title\": \"" + loan.getBookTitle() + "\", \"lateFee\": " + loan.getLateFee() + "}";

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create("https://inventory.acme.com/api/returns"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build();

			HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 400) {
				_log.error("Inventory rejected return of " + loan.getBookTitle() + ": " + response.body());
			}
		}
		catch (Exception e) {
			throw new ModelListenerException(e);
		}
	}

	@Override
	public void onAfterCreate(Loan loan) throws ModelListenerException {
		_log.info("Loan created for " + loan.getBorrowerEmail());
	}

	private static final Log _log = LogFactoryUtil.getLog(LoanModelListener.class);

}
