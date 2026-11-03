package com.acme.loans.service.impl;

import com.acme.loans.exception.LoanBorrowerEmailException;
import com.acme.loans.model.Loan;
import com.acme.loans.service.base.LoanLocalServiceBaseImpl;
import com.liferay.portal.aop.AopService;
import com.liferay.portal.kernel.util.Validator;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Component;

@Component(property = "model.class.name=com.acme.loans.model.Loan", service = AopService.class)
public class LoanLocalServiceImpl extends LoanLocalServiceBaseImpl {

	/** A loan lasts 14 days; the borrower must have a valid e-mail address. */
	public Loan addLoan(String bookTitle, String borrowerEmail) throws Exception {
		if (!Validator.isEmailAddress(borrowerEmail)) {
			throw new LoanBorrowerEmailException("Invalid e-mail: " + borrowerEmail);
		}

		Loan loan = loanPersistence.create(counterLocalService.increment());

		Date now = new Date();

		loan.setBookTitle(bookTitle);
		loan.setBorrowerEmail(borrowerEmail);
		loan.setLoanDate(now);
		loan.setDueDate(new Date(now.getTime() + TimeUnit.DAYS.toMillis(14)));
		loan.setStatus("onLoan");

		return loanPersistence.update(loan);
	}

	/** Late returns pay 0.50 per day after the due date. */
	public Loan returnLoan(long loanId) throws Exception {
		Loan loan = loanPersistence.findByPrimaryKey(loanId);

		Date now = new Date();

		long daysLate = TimeUnit.MILLISECONDS.toDays(now.getTime() - loan.getDueDate().getTime());

		loan.setReturnDate(now);
		loan.setLateFee(daysLate > 0 ? daysLate * 0.5 : 0);
		loan.setStatus("returned");

		return loanPersistence.update(loan);
	}

	public List<Loan> getOverdueLoans(Date date) {
		return loanFinder.findByDueDateBeforeAndStatus(date, "onLoan");
	}

}
