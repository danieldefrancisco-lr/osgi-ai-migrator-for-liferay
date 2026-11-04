<!DOCTYPE html>

<#include init />

<html class="${root_css_class}" dir="<@liferay.language key="lang.dir" />" lang="${w3c_language_id}">
<head>
	<title>${the_title} - ${company_name}</title>
	<@liferay_util["include"] page=top_head_include />
</head>
<body class="${css_class}">
<@liferay.control_menu />
<div id="wrapper">
	<header class="acme-header" id="banner">
		<a class="logo" href="${site_default_url}"><img alt="${logo_description}" src="${site_logo}" /></a>
		<span class="site-name">${site_name}</span>
		<#if getterUtil.getBoolean(themeDisplay.getThemeSetting("show-header-search"))>
			<div class="acme-search"><@liferay.search_bar /></div>
		</#if>
		<#include "${full_templates_path}/navigation.ftl" />
	</header>
	<section id="content">
		<@liferay_util["include"] page=content_include />
	</section>
	<footer class="acme-footer" id="footer">
		<p><@liferay.language key="powered-by" /> Acme</p>
	</footer>
</div>
<@liferay_util["include"] page=body_bottom_include />
</body>
</html>
