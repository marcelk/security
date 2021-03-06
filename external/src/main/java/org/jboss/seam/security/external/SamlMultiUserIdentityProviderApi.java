/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.seam.security.external;

import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.jboss.seam.security.external.jaxb.samlv2.assertion.AttributeType;
import org.jboss.seam.security.external.saml.api.SamlIdpSession;
import org.jboss.seam.security.external.saml.api.SamlNameId;

/**
 * @author Marcel Kolsteren
 * 
 */
public interface SamlMultiUserIdentityProviderApi
{
   void authenticationSucceeded(SamlIdpSession session, HttpServletResponse response);

   void authenticationFailed(HttpServletResponse response);

   Set<SamlIdpSession> getSessions();

   SamlIdpSession localLogin(SamlNameId nameId, List<AttributeType> attributes);

   SamlNameId createNameId(String value, String format, String qualifier);

   void remoteLogin(String spEntityId, SamlIdpSession session, String remoteUrl, HttpServletResponse response);

   void localLogout(SamlIdpSession session);

   void globalLogout(SamlIdpSession session, HttpServletResponse response);
}
