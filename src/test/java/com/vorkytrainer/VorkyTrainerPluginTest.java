package com.vorkytrainer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class VorkyTrainerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(VorkyTrainerPlugin.class);
		RuneLite.main(args);
	}
}