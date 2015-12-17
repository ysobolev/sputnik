#!/usr/bin/env python

from setuptools import setup, find_packages

setup(name="sputnik",
      version="1.3.0",
      description="Sputnik Exchange",
      author="Mimetic Markets",
      url="https://m2.io",
      packages=find_packages(),
      package_data={"sputnik.templates": ["*.html", "*.email"],
                    "sputnik.specs": ["objects/*.json", "rpc/*.json",
                                      "public/*.json"]},
      install_requires=["Twisted", "txZMQ==0.7.3", "jsonschema>=2.3.0",
                        "autobahn==0.9.5"]
     )
      
