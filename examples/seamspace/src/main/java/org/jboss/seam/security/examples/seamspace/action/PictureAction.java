package org.jboss.seam.security.examples.seamspace.action;


import java.io.Serializable;

import javax.enterprise.context.Conversation;
import javax.enterprise.context.ConversationScoped;
import javax.inject.Inject;
import javax.inject.Named;
import javax.persistence.EntityManager;

import org.jboss.seam.security.examples.seamspace.model.Member;
import org.jboss.seam.security.examples.seamspace.model.MemberImage;

@Named
@ConversationScoped
public class PictureAction implements Serializable
{
   private static final long serialVersionUID = -3768106941393643629L;

   private MemberImage memberImage;
   
   @Inject Member authenticatedMember;
   
   @Inject EntityManager entityManager;
   
   @Inject Conversation conversation;
      
   public void uploadPicture()
   {
      conversation.begin();
      memberImage = new MemberImage();
   }
   
   public void savePicture()
   {
      memberImage.setMember(entityManager.find(Member.class, authenticatedMember.getMemberId()));
      entityManager.persist(memberImage);
      conversation.end();
   }

   public MemberImage getMemberImage()
   {
      return memberImage;
   }
}
