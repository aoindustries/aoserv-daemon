/*
 * Copyright 2000-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.unix;

import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.util.StringUtility;
import java.util.List;

/**
 * A <code>ShadowFileEntry</code> represents one line of the
 * <code>/etc/shadow</code> file on a Unix server.
 *
 * @author  AO Industries, Inc.
 */
final public class ShadowFileEntry {

	/** The username the entry is for */
	public String username;

	/** The encrypted password */
	public String password;

	/** The days since Jan 1, 1970 password was last changed */
	public int changedDate;

	/** The number of days until a password change is allowed */
	public int minPasswordAge;

	/** The number of days until a password change is forced */
	public int maxPasswordAge;

	/** The days before password is to expire that user is warned of pending password expiration */
	public int warningDays;

	/** The days after password expires that account is considered inactive and disabled */
	public int inactivateDays;

	/** The days since Jan 1, 1970 when account will be disabled */
	public int expirationDate;

	/** Reserved for future use */
	public String flag;

	/**
	 * Constructs a shadow file entry given one line of the <code>/etc/shadow</code> file, not including
	 * the trailing newline (<code>'\n'</code>).  This may also be called providing only the username,
	 * in which case the default values are used and the password is set to <code>"!!"</code> (disabled).
	 */
	public ShadowFileEntry(String line) {
		List<String> values=StringUtility.splitString(line, ':');
		int len=values.size();
		if(len<1) throw new IllegalArgumentException("At least the first field of shadow file required: "+line);

		username = values.get(0);

		String S;

		if(len>=2 && (S=values.get(1)).length()>0) {
			password=S;
			// Convert * to !!
			if("*".equals(password)) password=LinuxAccount.NO_PASSWORD_CONFIG_VALUE;
		} else password=LinuxAccount.NO_PASSWORD_CONFIG_VALUE;

		if(len>=3 && (S=values.get(2)).length()>0) changedDate=Integer.parseInt(S);
		else changedDate=getCurrentDate();

		if(len>=4 && (S=values.get(3)).length()>0) minPasswordAge=Integer.parseInt(S);
		else minPasswordAge=-1;

		if(len>=5 && (S=values.get(4)).length()>0) maxPasswordAge=Integer.parseInt(S);
		else maxPasswordAge=99999;

		if(len>=6 && (S=values.get(5)).length()>0) warningDays=Integer.parseInt(S);
		else warningDays=0;

		if(len>=7 && (S=values.get(6)).length()>0) inactivateDays=Integer.parseInt(S);
		else inactivateDays=0;

		if(len>=8 && (S=values.get(7)).length()>0) expirationDate=Integer.parseInt(S);
		else expirationDate=0;

		if(len>=9 && (S=values.get(8)).length()>0) flag=S;
		else flag=null;
	}

	/**
	 * Constructs a shadow file entry given all the values.
	 */
	public ShadowFileEntry(
		String username,
		String password,
		int changedDate,
		int minPasswordAge,
		int maxPasswordAge,
		int warningDays,
		int inactivateDays,
		int expirationDate,
		String flag
	) {
		this.username = username;
		this.password = password;
		if("*".equals(password)) this.password=LinuxAccount.NO_PASSWORD_CONFIG_VALUE;
		this.changedDate = changedDate;
		this.minPasswordAge = minPasswordAge;
		this.maxPasswordAge = maxPasswordAge;
		this.warningDays = warningDays;
		this.inactivateDays = inactivateDays;
		this.expirationDate = expirationDate;
		this.flag = flag;
	}

	/**
	 * Gets the number of days from the Epoch for the current day.
	 */
	public static int getCurrentDate() {
		return getCurrentDate(System.currentTimeMillis());
	}

	/**
	 * Gets the number of days from the Epoch for the provided time in milliseconds from Epoch.
	 */
	public static int getCurrentDate(long time) {
		return (int)(time/(24*60*60*1000));
	}

	/**
	 * Gets this <code>ShadowFileEntry</code> as it would be written in <code>/etc/shadow</code>.
	 */
	@Override
	public String toString() {
		StringBuilder SB=new StringBuilder();
		SB
			.append(username)
			.append(':')
			.append(password)
			.append(':')
			.append(changedDate)
			.append(':')
		;
		if(minPasswordAge>=0) SB.append(minPasswordAge);
		SB.append(':').append(maxPasswordAge).append(':');
		if(warningDays>0) SB.append(warningDays);
		SB.append(':');
		if(inactivateDays>0) SB.append(inactivateDays);
		SB.append(':');
		if(expirationDate>0) SB.append(expirationDate);
		SB.append(':');
		if(flag!=null) SB.append(flag);
		return SB.toString();
	}
}