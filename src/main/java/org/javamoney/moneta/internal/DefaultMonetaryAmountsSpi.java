/*
 * CREDIT SUISSE IS WILLING TO LICENSE THIS SPECIFICATION TO YOU ONLY UPON THE CONDITION THAT YOU
 * ACCEPT ALL OF THE TERMS CONTAINED IN THIS AGREEMENT. PLEASE READ THE TERMS AND CONDITIONS OF THIS
 * AGREEMENT CAREFULLY. BY DOWNLOADING THIS SPECIFICATION, YOU ACCEPT THE TERMS AND CONDITIONS OF
 * THE AGREEMENT. IF YOU ARE NOT WILLING TO BE BOUND BY IT, SELECT THE "DECLINE" BUTTON AT THE
 * BOTTOM OF THIS PAGE. Specification: JSR-354 Money and Currency API ("Specification") Copyright
 * (c) 2012-2013, Credit Suisse All rights reserved.
 */
package org.javamoney.moneta.internal;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.money.MonetaryAmount;
import javax.money.MonetaryAmountFactory;
import javax.money.MonetaryContext;
import javax.money.MonetaryContext.AmountFlavor;
import javax.money.MonetaryException;
import javax.money.spi.Bootstrap;
import javax.money.spi.MonetaryAmountFactoryProviderSpi;
import javax.money.spi.MonetaryAmountFactoryProviderSpi.QueryInclusionPolicy;
import javax.money.spi.MonetaryAmountsSpi;
import javax.money.spi.MonetaryLogger;

public class DefaultMonetaryAmountsSpi implements MonetaryAmountsSpi {

	private Map<Class<? extends MonetaryAmount>, MonetaryAmountFactoryProviderSpi<?>> factories = new ConcurrentHashMap<>();

	private Class<? extends MonetaryAmount> configuredDefaultAmountType = loadDefaultAmountType();

	private static final Comparator<MonetaryAmountFactoryProviderSpi<? extends MonetaryAmount>> CONTEXT_COMPARATOR = new Comparator<MonetaryAmountFactoryProviderSpi<? extends MonetaryAmount>>() {

		@Override
		public int compare(
				MonetaryAmountFactoryProviderSpi<? extends MonetaryAmount> f1,
				MonetaryAmountFactoryProviderSpi<? extends MonetaryAmount> f2) {
			int compare = 0;
			MonetaryContext c1 = f1.getMaximalMonetaryContext();
			MonetaryContext c2 = f2.getMaximalMonetaryContext();
			if (c1.getAmountFlavor() == AmountFlavor.PRECISION
					&& c2.getAmountFlavor() != AmountFlavor.PRECISION) {
				compare = -1;
			}
			if (compare == 0 && c2.getAmountFlavor() == AmountFlavor.PRECISION
					&& c1.getAmountFlavor() != AmountFlavor.PRECISION) {
				compare = 1;
			}
			if (compare == 0 && c1.getPrecision() == 0
					&& c2.getPrecision() != 0) {
				compare = -1;
			}
			if (compare == 0 && c2.getPrecision() == 0
					&& c1.getPrecision() != 0) {
				compare = 1;
			}
			if (compare == 0 && (c1.getMaxScale() > c2.getMaxScale())) {
				compare = -1;
			}
			if (compare == 0 && (c1.getMaxScale() < c2.getMaxScale())) {
				compare = 1;
			}
			return compare;
		}
	};

	public DefaultMonetaryAmountsSpi() {
		for (MonetaryAmountFactoryProviderSpi<?> f : Bootstrap
				.getServices(MonetaryAmountFactoryProviderSpi.class)) {
			MonetaryAmountFactoryProviderSpi<?> existing = factories.put(
					f.getAmountType(), f);
			if (existing != null) {
				int compare = Bootstrap.comparePriority(existing, f);
				if (compare < 0) {
					Bootstrap.getService(MonetaryLogger.class).logWarning(
							"MonetaryAmountFactoryProviderSpi with lower prio ignored: "
									+ f);
					factories.put(f.getAmountType(), existing);
				}
				else if (compare == 0) {
					throw new IllegalStateException(
							"Ambigous MonetaryAmountFactoryProviderSpi found for "
									+ f.getAmountType() + ": "
									+ f.getClass().getName() + '/'
									+ existing.getClass().getName());
				}
			}
		}
	}

	/**
	 * Tries to load the default {@link MonetaryAmount} class from {@code javamoney.properties} with
	 * contents as follows:<br/>
	 * <code>
	 * javax.money.defaults.amount.class=my.fully.qualified.ClassName
	 * </code>
	 * 
	 * @return the loaded default class, or {@code null}
	 */
	// type check should be safe, exception will be logged if not.
	@SuppressWarnings("unchecked")
	private Class<? extends MonetaryAmount> loadDefaultAmountType() {
		URL res = getClass().getResource("javamoney.properties");
		if (res != null) {
			try (InputStream is = res.openStream()) {
				Properties props = new Properties();
				props.load(is);
				String defaultClass = props
						.getProperty("javax.money.defaults.amount.class");
				if (defaultClass != null) {
					return (Class<? extends MonetaryAmount>) Class
							.forName(defaultClass, true,
									getClassLoader());
				}
			} catch (Exception e) {
				Bootstrap
						.getService(MonetaryLogger.class)
						.logError(
								"Failed to initialize default MonetaryAmount type from javamoney.properties.",
								e);
			}
		}
		return null;
	}

	/**
	 * Evaluates the {@link ClassLoader} to use, uses by default the current context
	 * {@link ClassLoader}, following by the loader of this class itself.
	 * 
	 * @return the {@link ClassLoader} for loading.
	 */
	private ClassLoader getClassLoader() {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		if (cl == null) {
			cl = getClass().getClassLoader();
		}
		return cl;
	}

	// save cast, since members are managed by this instance
	@SuppressWarnings("unchecked")
	@Override
	public <T extends MonetaryAmount> MonetaryAmountFactory<T> getAmountFactory(
			Class<T> amountType) {
		MonetaryAmountFactoryProviderSpi<T> f = MonetaryAmountFactoryProviderSpi.class
				.cast(factories
						.get(amountType));
		if (f != null) {
			return f.createMonetaryAmountFactory();
		}
		throw new MonetaryException(
				"No matching MonetaryAmountFactory found, type="
						+ amountType.getName());
	}

	@Override
	public Set<Class<? extends MonetaryAmount>> getAmountTypes() {
		return factories.keySet();
	}

	/*
	 * (non-Javadoc)
	 * @see javax.money.spi.MonetaryAmountsSpi#getDefaultAmountType()
	 */
	@Override
	public Class<? extends MonetaryAmount> getDefaultAmountType() {
		if (configuredDefaultAmountType == null) {
			for (MonetaryAmountFactoryProviderSpi<?> f : Bootstrap
					.getServices(MonetaryAmountFactoryProviderSpi.class)) {
				configuredDefaultAmountType = f.getAmountType();
				break;
			}
		}
		if (configuredDefaultAmountType == null) {
			throw new MonetaryException(
					"No MonetaryAmountFactoryProviderSpi registered.");
		}
		return configuredDefaultAmountType;
	}

	/**
	 * (non-Javadoc)
	 * 
	 * @see javax.money.spi.MonetaryAmountsSpi#queryAmountType(javax.money.MonetaryContext)
	 */
	@Override
	public Class<? extends MonetaryAmount> queryAmountType(
			MonetaryContext requiredContext) {
		if (requiredContext == null) {
			return getDefaultAmountType();
		}
		// first check for explicit type
		for (@SuppressWarnings("unchecked")
		MonetaryAmountFactoryProviderSpi<? extends MonetaryAmount> f : Bootstrap
				.getServices(MonetaryAmountFactoryProviderSpi.class)) {
			if (f.getQueryInclusionPolicy() == QueryInclusionPolicy.NEVER) {
				continue;
			}
			if (requiredContext.getAmountType() == f.getAmountType()) {
				if (isPrecisionOK(requiredContext,
						f.getMaximalMonetaryContext())) {
					return f.getAmountType();
				}
				else {
					throw new MonetaryException(
							"Incompatible context required=" + requiredContext
									+ ", maximal="
									+ f.getMaximalMonetaryContext());
				}
			}
		}
		// Select on required flavor
		List<MonetaryAmountFactoryProviderSpi<? extends MonetaryAmount>> selection = new ArrayList<>();
		for (@SuppressWarnings("unchecked")
		MonetaryAmountFactoryProviderSpi<? extends MonetaryAmount> f : Bootstrap
				.getServices(MonetaryAmountFactoryProviderSpi.class)) {
			if (f.getDefaultMonetaryContext().getAmountFlavor() == AmountFlavor.UNDEFINED) {
				if (f.getQueryInclusionPolicy() == QueryInclusionPolicy.DIRECT_REFERENCE_ONLY
						||
						f.getQueryInclusionPolicy() == QueryInclusionPolicy.NEVER) {
					continue;
				}
				if (isPrecisionOK(requiredContext,
						f.getMaximalMonetaryContext())) {
					selection.add(f);
				}
			}
			else if (requiredContext.getAmountFlavor() == f
					.getDefaultMonetaryContext().getAmountFlavor()) {
				if (isPrecisionOK(requiredContext,
						f.getMaximalMonetaryContext())) {
					selection.add(f);
				}
			}
		}
		if (selection.isEmpty()) {
			// fall back, add all selections, ignore flavor
			for (@SuppressWarnings("unchecked")
			MonetaryAmountFactoryProviderSpi<? extends MonetaryAmount> f : Bootstrap
					.getServices(MonetaryAmountFactoryProviderSpi.class)) {
				if (f.getQueryInclusionPolicy() == QueryInclusionPolicy.DIRECT_REFERENCE_ONLY
						||
						f.getQueryInclusionPolicy() == QueryInclusionPolicy.NEVER) {
					continue;
				}
				selection.add(f);
			}
		}
		if (selection.size() == 1) {
			return selection.get(0).getAmountType();
		}
		Collections.sort(selection, CONTEXT_COMPARATOR);
		return selection.get(0).getAmountType();
	}

	private boolean isPrecisionOK(MonetaryContext requiredContext,
			MonetaryContext maximalMonetaryContext) {
		if (requiredContext.getPrecision() == 0
				&& maximalMonetaryContext.getPrecision() != 0) {
			return false;
		}
		if (requiredContext.getPrecision() > maximalMonetaryContext
				.getPrecision()) {
			return false;
		}
		if (requiredContext.getMaxScale() > maximalMonetaryContext
				.getMaxScale()) {
			return false;
		}
		return true;
	}

}
