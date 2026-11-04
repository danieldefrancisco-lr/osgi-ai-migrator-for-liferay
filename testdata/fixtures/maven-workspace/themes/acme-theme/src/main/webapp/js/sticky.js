window.addEventListener('scroll', function () {
	document.body.classList.toggle('acme-sticky', window.scrollY > 80);
});
